/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Validation rules of the document template configuration (ETP-5988): document types, variants,
 * footer and logo.
 *
 * @covers com.etendoerp.go.schemaforge.DocTemplateConfigSupport
 * @covers com.etendoerp.go.schemaforge.DocTemplateConfig
 */
@DisplayName("ETP-5988 — DocTemplateConfigSupport")
class DocTemplateConfigSupportTest {

  private static final String PNG = "data:image/png;base64,iVBORw0KGgo=";

  @ParameterizedTest
  @ValueSource(strings = { "sales-invoice", "sales-order", "sales-quotation", "purchase-order",
      "goods-shipment", "payment-in", "return-material-receipt", "return-to-vendor-shipment", "listing" })
  @DisplayName("every printable's document type, and the list print, is accepted")
  void acceptsTheKnownDocTypes(String docType) {
    assertNull(DocTemplateConfigSupport.docTypeError(docType));
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "  ", "Sales-Invoice", "invoice", "print-sales-invoice" })
  @DisplayName("a blank or unknown document type is refused")
  void refusesUnknownDocTypes(String docType) {
    assertNotNull(DocTemplateConfigSupport.docTypeError(docType));
    assertNotNull(DocTemplateConfigSupport.docTypeError(null));
  }

  @Test
  @DisplayName("only classic, compact and modern are variants")
  void variants() {
    assertNull(DocTemplateConfigSupport.variantError("classic"));
    assertNull(DocTemplateConfigSupport.variantError("compact"));
    assertNull(DocTemplateConfigSupport.variantError("modern"));
    assertNotNull(DocTemplateConfigSupport.variantError("Modern"));
    assertNotNull(DocTemplateConfigSupport.variantError(""));
    assertNotNull(DocTemplateConfigSupport.variantError(null));
  }

  @Test
  @DisplayName("the footer may be absent, is capped, and refuses control characters but keeps line breaks")
  void footer() {
    assertNull(DocTemplateConfigSupport.footerError(null));
    assertNull(DocTemplateConfigSupport.footerError("Thanks for your business\nwww.example.com"));
    assertNull(DocTemplateConfigSupport.footerError("x".repeat(DocTemplateConfigSupport.MAX_FOOTER_LENGTH)));
    assertNotNull(DocTemplateConfigSupport.footerError("x".repeat(DocTemplateConfigSupport.MAX_FOOTER_LENGTH + 1)));
    assertNotNull(DocTemplateConfigSupport.footerError("bad\u0000footer"));
    assertNotNull(DocTemplateConfigSupport.footerError("bad\u0007footer"));
  }

  @Test
  @DisplayName("a logo is an https URL or a png/jpeg/webp data URI")
  void acceptedLogos() {
    assertNull(DocTemplateConfigSupport.logoError(null));
    assertNull(DocTemplateConfigSupport.logoError("https://cdn.example.com/logo.png"));
    assertNull(DocTemplateConfigSupport.logoError(PNG));
    assertNull(DocTemplateConfigSupport.logoError("data:image/jpeg;base64,/9j/4AAQ"));
    assertNull(DocTemplateConfigSupport.logoError("data:image/webp;base64,UklGRg=="));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "http://cdn.example.com/logo.png",
      "javascript:alert(1)",
      "ftp://cdn.example.com/logo.png",
      "https://",
      "https://cdn.example.com/a b.png",
      "https://cdn.example.com/\"onload=1",
      "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=",
      "data:image/png;charset=utf-8,abc",
      "data:image/png;base64,",
      "data:image/png;base64,@@@@",
      "data:image/png;base64,AB=C",
      "data:text/html;base64,PGh0bWw+",
      "/relative/logo.png"
  })
  @DisplayName("an unsafe or malformed logo is refused")
  void refusedLogos(String logo) {
    assertNotNull(DocTemplateConfigSupport.logoError(logo), logo);
  }

  @Test
  @DisplayName("logo size is capped for both forms")
  void logoSize() {
    String longUrl = "https://example.com/" + "a".repeat(DocTemplateConfigSupport.MAX_LOGO_URL_LENGTH);
    String bigData = "data:image/png;base64," + "A".repeat(DocTemplateConfigSupport.MAX_LOGO_DATA_LENGTH);
    assertNotNull(DocTemplateConfigSupport.logoError(longUrl));
    assertNotNull(DocTemplateConfigSupport.logoError(bigData));
  }

  @Test
  @DisplayName("blank text is stored as not set; other text is trimmed")
  void normalize() {
    assertNull(DocTemplateConfigSupport.normalize(null));
    assertNull(DocTemplateConfigSupport.normalize("   "));
    assertEquals("Thanks", DocTemplateConfigSupport.normalize("  Thanks \n"));
  }

  @Test
  @DisplayName("the defaults are classic, no logo, no footer, not configured")
  void defaults() throws Exception {
    DocTemplateConfig defaults = DocTemplateConfig.defaults("ORG1", "sales-invoice");

    assertEquals("classic", defaults.toJson().getString("variant"));
    assertEquals(false, defaults.toJson().getBoolean("configured"));
    assertEquals(true, defaults.toJson().isNull("logo"));
    assertEquals(true, defaults.toJson().isNull("footerText"));
    assertEquals("ORG1", defaults.toJson().getString("orgId"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "#000000", "#FFFFFF", "#1a73e8", "#1A73E8" })
  @DisplayName("a #RRGGBB base color is accepted in either case")
  void acceptsHexColors(String color) {
    assertNull(DocTemplateConfigSupport.colorError(color));
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "red", "#FFF", "#12345", "#1234567", "#12345G", "123456",
      "#123456;}", "#123456\n", "rgb(0,0,0)", "url(x)" })
  @DisplayName("anything but #RRGGBB is refused as a color: it ends up inside a stylesheet")
  void refusesOtherColors(String color) {
    assertNotNull(DocTemplateConfigSupport.colorError(color));
  }

  @Test
  @DisplayName("an unset color or choice is acceptable and means the design's default")
  void unsetStyleIsAcceptable() {
    assertNull(DocTemplateConfigSupport.colorError(null));
    assertNull(DocTemplateConfigSupport.fontError(null));
    assertNull(DocTemplateConfigSupport.fontSizeError(null));
    assertNull(DocTemplateConfigSupport.rowStripesError(null));
  }

  @Test
  @DisplayName("font, font size and row stripes accept only their vocabulary")
  void styleChoices() {
    assertNull(DocTemplateConfigSupport.fontError("serif"));
    assertNotNull(DocTemplateConfigSupport.fontError("comic"));
    assertNull(DocTemplateConfigSupport.fontSizeError("large"));
    assertNotNull(DocTemplateConfigSupport.fontSizeError("huge"));
    assertNull(DocTemplateConfigSupport.rowStripesError("off"));
    assertNotNull(DocTemplateConfigSupport.rowStripesError("yes"));
    assertNull(DocTemplateConfigSupport.showLogoError("on"));
    assertNull(DocTemplateConfigSupport.showLogoError(null));
    assertNotNull(DocTemplateConfigSupport.showLogoError("true"));
  }

  @Test
  @DisplayName("a color is stored upper-cased and trimmed; blank is unset")
  void normalizesColor() {
    assertEquals("#1A73E8", DocTemplateConfigSupport.normalizeColor(" #1a73e8 "));
    assertNull(DocTemplateConfigSupport.normalizeColor("  "));
    assertNull(DocTemplateConfigSupport.normalizeColor(null));
  }
}
