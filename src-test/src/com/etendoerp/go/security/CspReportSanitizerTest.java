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
package com.etendoerp.go.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Abuse/regression tests for the CSP Report-Only collector: reports must never persist query
 * strings, tokens, credentials or PII, and violations must be classified.
 *
 * @covers com.etendoerp.go.security.CspReportSanitizer
 */
class CspReportSanitizerTest {

  private static String legacy(String blocked, String doc, String directive) {
    return "{\"csp-report\":{\"document-uri\":\"" + doc + "\",\"blocked-uri\":\"" + blocked
        + "\",\"effective-directive\":\"" + directive + "\",\"violated-directive\":\"" + directive
        + "\",\"disposition\":\"report\",\"original-policy\":\"default-src 'self'; report-uri /x?k=SECRET\","
        + "\"script-sample\":\"user@example.com token=abc\",\"referrer\":\"https://a.test/?t=1\"}}";
  }

  @Test
  void stripsQueryFragmentAndUserInfoFromUrls() throws Exception {
    Optional<JSONObject> out = CspReportSanitizer.sanitize(legacy(
        "https://evil.test/p?access_token=SECRET&email=a@b.com#frag",
        "https://go.staging.etendo.cloud/app?code=OAUTHCODE", "connect-src"));
    String s = out.orElseThrow().toString();
    assertFalse(s.contains("SECRET"));
    assertFalse(s.contains("OAUTHCODE"));
    assertFalse(s.contains("a@b.com"));
    assertFalse(s.contains("frag"));
    assertEquals("https://evil.test/p", out.get().getString("blockedUri"));
    assertEquals("https://go.staging.etendo.cloud/app", out.get().getString("documentUri"));
  }

  @Test
  void dropsFreeTextFieldsThatCanCarryPii() throws Exception {
    String s = CspReportSanitizer.sanitize(legacy("inline", "https://go.test/", "script-src"))
        .orElseThrow().toString();
    assertFalse(s.contains("user@example.com"));
    assertFalse(s.contains("SECRET"));
    assertFalse(s.contains("referrer"));
    assertFalse(s.contains("script-sample"));
    assertFalse(s.contains("original-policy"));
  }

  @Test
  void redactsTokenLikeAndPiiPathSegments() throws Exception {
    JSONObject o = CspReportSanitizer.sanitize(legacy(
        "https://cdn.test/a/0123456789abcdef0123456789abcdef/jane.doe@corp.com/x.js",
        "https://go.test/", "script-src-elem")).orElseThrow();
    assertEquals("https://cdn.test/a/[redacted]/[redacted]/x.js", o.getString("blockedUri"));
  }

  @Test
  void keepsOnlySchemeOfBlobAndDataUris() throws Exception {
    JSONObject o = CspReportSanitizer.sanitize(
        legacy("blob:https://go.test/3f2c1a9e-aaaa-bbbb-cccc-0123456789ab", "https://go.test/", "img-src"))
        .orElseThrow();
    assertEquals("blob", o.getString("blockedUri"));
    assertEquals("INVENTORY_BLOB", o.getString("classification"));
  }

  @Test
  void parsesReportingApiPayloadAndSanitizesIt() throws Exception {
    String body = "[{\"type\":\"csp-violation\",\"url\":\"https://go.test/?s=1\",\"body\":{"
        + "\"documentURL\":\"https://go.test/x?token=T\",\"blockedURL\":\"https://t.test/c?id=9\","
        + "\"effectiveDirective\":\"connect-src\",\"sample\":\"secret\"}}]";
    JSONObject o = CspReportSanitizer.sanitize(body).orElseThrow();
    assertEquals("https://t.test/c", o.getString("blockedUri"));
    assertEquals("https://go.test/x", o.getString("documentUri"));
    assertFalse(o.toString().contains("secret"));
  }

  @Test
  void classifiesExtensionsAndUnexpected() throws Exception {
    assertEquals("EXPECTED_EXTENSION", CspReportSanitizer.sanitize(
        legacy("chrome-extension://abcdef/x.js", "https://go.test/", "script-src")).orElseThrow()
        .getString("classification"));
    assertEquals("INVENTORY_TELEMETRY", CspReportSanitizer.sanitize(legacy(
        "https://dataplane.rum.eu-west-3.amazonaws.com/appmonitors/x", "https://go.test/", "connect-src"))
        .orElseThrow().getString("classification"));
    assertEquals("UNEXPECTED", CspReportSanitizer.sanitize(
        legacy("https://evil.test/x.js", "https://go.test/", "script-src")).orElseThrow()
        .getString("classification"));
  }

  @Test
  void rejectsMalformedOrOversizedInput() {
    assertTrue(CspReportSanitizer.sanitize("not json").isEmpty());
    assertTrue(CspReportSanitizer.sanitize("{}").isEmpty());
    assertTrue(CspReportSanitizer.sanitize(null).isEmpty());
    assertTrue(CspReportSanitizer.sanitize("{\"csp-report\":{\"blocked-uri\":\""
        + "a".repeat(CspReportSanitizer.MAX_BODY_CHARS) + "\"}}").isEmpty());
  }

  @Test
  void truncatesLongPaths() throws Exception {
    JSONObject o = CspReportSanitizer.sanitize(legacy("https://x.test/" + "p/".repeat(300),
        "https://go.test/", "script-src")).orElseThrow();
    assertTrue(o.getString("blockedUri").length() <= CspReportSanitizer.MAX_FIELD_CHARS);
  }

  @Test
  void redactsPresignedAttachmentUrlQueryAndKeepsExactPath() throws Exception {
    JSONObject o = CspReportSanitizer.sanitize(legacy(
        "https://bucket.s3.eu-west-1.amazonaws.com/att/invoice.pdf?X-Amz-Signature=deadbeef"
            + "&X-Amz-Credential=AKIAXYZ&Signature=abc&Key-Pair-Id=K123&Expires=1",
        "https://go.test/", "frame-src")).orElseThrow();
    assertEquals("https://bucket.s3.eu-west-1.amazonaws.com/att/invoice.pdf",
        o.getString("blockedUri"));
    assertFalse(o.toString().matches("(?s).*(Signature|AKIA|Key-Pair|deadbeef).*"));
  }

  @Test
  void redactsUuidAndEtendoRecordIdsInDocumentPath() throws Exception {
    JSONObject o = CspReportSanitizer.sanitize(legacy("inline",
        "https://go.test/orders/3f2c1a9e-aaaa-bbbb-cccc-0123456789ab/lines/"
            + "0123456789ABCDEF0123456789ABCDEF/edit", "script-src")).orElseThrow();
    assertEquals("https://go.test/orders/[redacted]/lines/[redacted]/edit",
        o.getString("documentUri"));
  }

  @Test
  void redactsEncodedEmailSegment() throws Exception {
    JSONObject o = CspReportSanitizer.sanitize(legacy("https://x.test/u/jane%40corp.com/p",
        "https://go.test/", "img-src")).orElseThrow();
    assertEquals("https://x.test/u/[redacted]/p", o.getString("blockedUri"));
  }

  @Test
  void redactsJwtLikeSegment() throws Exception {
    JSONObject o = CspReportSanitizer.sanitize(legacy(
        "https://x.test/cb/eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.abc_DEF-ghi/z",
        "https://go.test/", "connect-src")).orElseThrow();
    assertEquals("https://x.test/cb/[redacted]/z", o.getString("blockedUri"));
  }

  @Test
  void keepsHostOfWebSocketUris() throws Exception {
    assertEquals("wss://rt.test/socket", CspReportSanitizer.sanitize(
        legacy("wss://rt.test/socket?token=T", "https://go.test/", "connect-src")).orElseThrow()
        .getString("blockedUri"));
  }

  @Test
  void redactsUrisWhoseHostCannotBeParsed() throws Exception {
    assertEquals("[redacted]", CspReportSanitizer.sanitize(
        legacy("https://bad_host.test/p?q=1", "https://go.test/", "img-src")).orElseThrow()
        .getString("blockedUri"));
  }

  @Test
  void classifiesPreviewAndSso() throws Exception {
    assertEquals("INVENTORY_PREVIEW", CspReportSanitizer.sanitize(
        legacy("https://go.test/attachments/x.pdf", "https://go.test/app", "frame-src"))
        .orElseThrow().getString("classification"));
    assertEquals("INVENTORY_PREVIEW", CspReportSanitizer.sanitize(
        legacy("data:image/png;base64,AAAA", "https://go.test/app", "img-src"))
        .orElseThrow().getString("classification"));
    assertEquals("INVENTORY_SSO", CspReportSanitizer.sanitize(
        legacy("https://accounts.google.com/gsi/client?x=1", "https://go.test/", "script-src"))
        .orElseThrow().getString("classification"));
    assertEquals("UNEXPECTED", CspReportSanitizer.sanitize(
        legacy("https://go.test/x.js", "https://go.test/", "script-src")).orElseThrow()
        .getString("classification"));
  }

  @Test
  void normalizesAndBoundsTheDirective() throws Exception {
    assertEquals("script-src-elem", CspReportSanitizer.sanitize(
        legacy("inline", "https://go.test/", "SCRIPT-SRC-ELEM 'self' https://x.test/?t=1"))
        .orElseThrow().getString("effectiveDirective"));
    assertEquals("", CspReportSanitizer.sanitize(
        legacy("inline", "https://go.test/", "a".repeat(200) + "1")).orElseThrow()
        .getString("effectiveDirective"));
  }
}
