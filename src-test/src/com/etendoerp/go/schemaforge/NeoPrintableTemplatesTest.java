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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.common.NeoCurrencyFormatConfig;

/**
 * ETP-6013 — the published printable as the backend reads it: validation, cache, labels per locale,
 * the helper placeholders and the jsreport payload. The manifest below has the shape
 * {@code printableSources.js#buildPrintableManifest} publishes.
 *
 * @covers com.etendoerp.go.schemaforge.NeoPrintableTemplates
 * @covers com.etendoerp.go.schemaforge.NeoJsreportClient
 */
@DisplayName("ETP-6013 — NeoPrintableTemplates")
class NeoPrintableTemplatesTest {

  private static final String HELPERS = "function __groupEsEs(num, minFrac, maxFrac) { return "
      + "\"@@SF_THOUSANDS_SEPARATOR@@\" + \"@@SF_DECIMAL_SEPARATOR@@\"; }\n"
      + "function formatNumber(value) { return __groupEsEs(value, @@SF_RATE_DECIMALS@@, "
      + "@@SF_RATE_DECIMALS@@); }";

  private static String manifest(String document) throws Exception {
    JSONObject m = new JSONObject();
    m.put("manifestVersion", 1);
    m.put("document", document);
    m.put("family", "commercial");
    m.put("builtAt", 1700000000000L);
    m.put("template", new JSONObject().put("content", "<html>{{documentNo}}</html>")
        .put("engine", "handlebars").put("recipe", "chrome-pdf")
        .put("chrome", new JSONObject().put("format", "A4")));
    m.put("css", "body{}");
    m.put("helpers", HELPERS);
    m.put("helperPlaceholders", new JSONObject()
        .put("thousandsSeparator", "@@SF_THOUSANDS_SEPARATOR@@")
        .put("decimalSeparator", "@@SF_DECIMAL_SEPARATOR@@")
        .put("rateDecimals", "@@SF_RATE_DECIMALS@@"));
    m.put("labels", new JSONObject()
        .put("es_ES", new JSONObject().put("title", "Factura"))
        .put("en_US", new JSONObject().put("title", "Invoice")));
    return m.toString();
  }

  @BeforeEach
  @AfterEach
  void clearOverride() {
    System.clearProperty(NeoPrintableTemplates.URL_PROPERTY);
  }

  @Test
  @DisplayName("a published manifest parses; labels fall back by language, then to en_US")
  void labelsPerLocale() throws Exception {
    NeoPrintableTemplates.Template template =
        NeoPrintableTemplates.Template.parse("sales-invoice", manifest("sales-invoice"));

    assertEquals("commercial", template.getFamily());
    assertEquals(1700000000000L, template.getBuiltAt());
    assertEquals("Factura", template.labelsFor("es_ES").getString("title"));
    assertEquals("Factura", template.labelsFor("es-AR").getString("title"), "same language");
    assertEquals("Invoice", template.labelsFor("fr_FR").getString("title"), "en_US fallback");
    assertEquals("Invoice", template.labelsFor(null).getString("title"));
  }

  @Test
  @DisplayName("the placeholders are filled the way JSON.stringify writes the separators")
  void helpersAreFilled() throws Exception {
    NeoPrintableTemplates.Template template =
        NeoPrintableTemplates.Template.parse("sales-invoice", manifest("sales-invoice"));

    String filled = template.fillHelpers(new NeoCurrencyFormatConfig(",", "."), 6);

    assertTrue(filled.contains("return \",\" + \".\";"), filled);
    assertTrue(filled.contains("__groupEsEs(value, 6, 6)"), filled);
    assertFalse(filled.contains("@@SF_"), filled);
    assertTrue(template.fillHelpers(new NeoCurrencyFormatConfig(".", ","), -1)
        .contains("__groupEsEs(value, 4, 4)"), "a negative precision falls back to 4");
  }

  @Test
  @DisplayName("the payload is the browser's: data after the css, labels added, helpers filled")
  void payloadShape() throws Exception {
    NeoPrintableTemplates.Template template =
        NeoPrintableTemplates.Template.parse("sales-invoice", manifest("sales-invoice"));

    JSONObject payload = template.payload(new JSONObject().put("documentNo", "INV-1"), "es_ES");

    JSONObject data = payload.getJSONObject("data");
    assertEquals("body{}", data.getString("css"));
    assertEquals("INV-1", data.getString("documentNo"));
    assertEquals("Factura", data.getJSONObject("labels").getString("title"));
    JSONObject sent = payload.getJSONObject("template");
    assertEquals("chrome-pdf", sent.getString("recipe"));
    assertTrue(sent.getString("helpers").contains("__groupEsEs(value, 4, 4)"),
        "no rateDecimals in the data: 4, as renderDocumentPdf");
  }

  @Test
  @DisplayName("not JSON (an SPA fallback page), another version, another document or a gap is refused")
  void invalidManifestsAreRefused() throws Exception {
    String[] bad = {"<!doctype html><html></html>",
        manifest("sales-invoice").replace("\"manifestVersion\":1", "\"manifestVersion\":2"),
        manifest("sales-order"),
        new JSONObject(manifest("sales-invoice")).put("labels", new JSONObject()).toString(),
        new JSONObject(manifest("sales-invoice")).put("helpers", "").toString()};
    for (String raw : bad) {
      assertThrows(NeoPrintableTemplates.ManifestException.class,
          () -> NeoPrintableTemplates.Template.parse("sales-invoice", raw), raw);
    }
  }

  @Test
  @DisplayName("fetched once per TTL from the configured location; a failure is not cached")
  void cacheAndLocation() throws Exception {
    System.setProperty(NeoPrintableTemplates.URL_PROPERTY, "http://localhost:3100/printables/");
    AtomicInteger fetches = new AtomicInteger();
    AtomicReference<String> url = new AtomicReference<>();
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-30T10:00:00Z"));
    Clock clock = new Clock() {
      @Override public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      @Override public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      @Override public Instant instant() {
        return now.get();
      }
    };
    NeoPrintableTemplates templates = new NeoPrintableTemplates(u -> {
      fetches.incrementAndGet();
      url.set(u);
      try {
        return manifest("sales-invoice");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }, clock);

    NeoPrintableTemplates.Template first = templates.get("sales-invoice");
    assertSame(first, templates.get("sales-invoice"));
    assertEquals(1, fetches.get());
    assertEquals("http://localhost:3100/printables/sales-invoice.json", url.get());
    now.set(now.get().plus(NeoPrintableTemplates.TTL).plus(Duration.ofSeconds(1)));
    templates.get("sales-invoice");
    assertEquals(2, fetches.get(), "refetched after the TTL");
    assertThrows(NeoPrintableTemplates.ManifestException.class, () -> templates.get("../etc"));

    NeoPrintableTemplates failing = new NeoPrintableTemplates(u -> {
      throw new IllegalStateException("HTTP 404");
    }, clock);
    assertThrows(NeoPrintableTemplates.ManifestException.class,
        () -> failing.get("sales-invoice"));
  }

  @Test
  @DisplayName("jsreport: only a body with the %PDF- magic is a PDF")
  void pdfMagic() {
    assertTrue(NeoJsreportClient.isPdf("%PDF-1.7".getBytes()));
    assertFalse(NeoJsreportClient.isPdf("<!doctype html>".getBytes()));
    assertFalse(NeoJsreportClient.isPdf(null));
  }
  @Test
  @DisplayName("both clients speak HTTP/1.1 and follow no redirect (an h2c Upgrade hangs the Vite dev server)")
  void clientsUseHttp11() {
    for (java.net.http.HttpClient client : new java.net.http.HttpClient[] {
        NeoPrintableTemplates.buildHttpClient(), NeoJsreportClient.buildHttpClient()}) {
      assertEquals(java.net.http.HttpClient.Version.HTTP_1_1, client.version());
      assertEquals(java.net.http.HttpClient.Redirect.NEVER, client.followRedirects());
    }
  }

}
