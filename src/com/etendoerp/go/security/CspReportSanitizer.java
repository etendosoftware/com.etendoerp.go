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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Collector-side sanitizer for CSP violation reports (ADR-0002 D7). It keeps an allowlist of
 * fields, reduces URLs to scheme+host+path (no userinfo, query or fragment), redacts token-like or
 * PII-like path segments and classifies the violation. Free-text fields (script sample, referrer,
 * original policy) are never kept.
 */
public final class CspReportSanitizer {

  public static final int MAX_BODY_CHARS = 16 * 1024;
  public static final int MAX_FIELD_CHARS = 256;
  static final String REDACTED = "[redacted]";

  private static final Set<String> TELEMETRY_HOSTS = Set.of("dataplane.rum.eu-west-3.amazonaws.com");
  /** Hosts of the Google sign-in flow; the SPA verifies identity server-side, so none is allowed. */
  private static final Set<String> SSO_HOSTS = Set.of("accounts.google.com", "apis.google.com",
      "www.gstatic.com");
  private static final Set<String> PREVIEW_DIRECTIVES = Set.of("frame-src", "img-src",
      "object-src", "media-src", "child-src");
  private static final Set<String> EXTENSION_SCHEMES = Set.of("chrome-extension", "moz-extension",
      "safari-extension", "safari-web-extension", "ms-browser-extension");
  private static final Set<String> WEB_SCHEMES = Set.of("http", "https", "ws", "wss");
  private static final Set<String> KEYWORDS = Set.of("inline", "eval", "self", "wasm-eval",
      "trusted-types-policy", "trusted-types-sink");
  private static final Pattern TOKEN_SEGMENT = Pattern.compile(
      "[0-9a-fA-F-]{16,}|[\\w+=.-]{24,}|\\d{6,}");

  private CspReportSanitizer() {
  }

  /**
   * Sanitizes one CSP report body.
   *
   * @param body raw request body, either a legacy {@code csp-report} object or a Reporting API array
   * @return the sanitized report, or empty when the body is not a usable CSP report
   */
  public static Optional<JSONObject> sanitize(String body) {
    if (body == null || body.length() > MAX_BODY_CHARS) {
      return Optional.empty();
    }
    try {
      JSONObject report = extractReport(body.trim());
      if (report == null) {
        return Optional.empty();
      }
      String blocked = uri(first(report, "blocked-uri", "blockedURL", "blockedURI"));
      String document = uri(first(report, "document-uri", "documentURL", "documentURI"));
      String directive = directive(first(report, "effective-directive", "effectiveDirective",
          "violated-directive", "violatedDirective"));
      if (blocked.isEmpty() && document.isEmpty() && directive.isEmpty()) {
        return Optional.empty();
      }
      JSONObject out = new JSONObject();
      out.put("blockedUri", blocked);
      out.put("documentUri", document);
      out.put("effectiveDirective", directive);
      out.put("disposition", "enforce".equals(first(report, "disposition")) ? "enforce" : "report");
      out.put("classification", classify(blocked, directive, document));
      return Optional.of(out);
    } catch (JSONException e) {
      return Optional.empty();
    }
  }

  private static JSONObject extractReport(String body) throws JSONException {
    if (body.startsWith("[")) {
      JSONArray arr = new JSONArray(body);
      for (int i = 0; i < arr.length(); i++) {
        JSONObject item = arr.optJSONObject(i);
        if (item != null && "csp-violation".equals(item.optString("type"))) {
          return item.optJSONObject("body");
        }
      }
      return null;
    }
    JSONObject root = new JSONObject(body);
    JSONObject legacy = root.optJSONObject("csp-report");
    if (legacy != null) {
      return legacy;
    }
    return "csp-violation".equals(root.optString("type")) ? root.optJSONObject("body") : null;
  }

  private static String first(JSONObject o, String... keys) {
    for (String k : keys) {
      String v = o.optString(k, "");
      if (!v.isEmpty()) {
        return v;
      }
    }
    return "";
  }

  private static String directive(String raw) {
    String d = raw.split("[\\s;]", 2)[0].toLowerCase(Locale.ROOT);
    return d.matches("[a-z-]{1,40}") ? d : "";
  }

  static String uri(String raw) {
    if (raw.isEmpty() || KEYWORDS.contains(raw)) {
      return raw;
    }
    if (raw.startsWith("data:")) {
      return "data";
    }
    if (raw.startsWith("blob:")) {
      return "blob";
    }
    try {
      URI u = new URI(raw);
      String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
      if (!WEB_SCHEMES.contains(scheme)) {
        return EXTENSION_SCHEMES.contains(scheme) ? scheme + "://" : REDACTED;
      }
      return u.getHost() == null ? REDACTED : cap(origin(u, scheme) + path(u.getRawPath()));
    } catch (URISyntaxException e) {
      return REDACTED;
    }
  }

  private static String origin(URI u, String scheme) {
    return scheme + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
  }

  private static String path(String rawPath) {
    if (rawPath == null) {
      return "";
    }
    return Arrays.stream(rawPath.split("/"))
        .filter(seg -> !seg.isEmpty())
        .map(CspReportSanitizer::redactSegment)
        .map(seg -> "/" + seg)
        .collect(Collectors.joining());
  }

  private static String redactSegment(String seg) {
    return seg.contains("@") || seg.toLowerCase(Locale.ROOT).contains("%40")
        || TOKEN_SEGMENT.matcher(seg).matches() ? REDACTED : seg;
  }

  private static String cap(String s) {
    return s.length() <= MAX_FIELD_CHARS ? s : s.substring(0, MAX_FIELD_CHARS);
  }

  static String classify(String blocked, String directive, String document) {
    if (blocked.equals("blob")) {
      return "INVENTORY_BLOB";
    }
    if (blocked.endsWith("://") && EXTENSION_SCHEMES.contains(blocked.replace("://", ""))) {
      return "EXPECTED_EXTENSION";
    }
    String host = host(blocked);
    if (TELEMETRY_HOSTS.contains(host)) {
      return "INVENTORY_TELEMETRY";
    }
    if (SSO_HOSTS.contains(host)) {
      return "INVENTORY_SSO";
    }
    boolean preview = PREVIEW_DIRECTIVES.contains(directive)
        && (blocked.equals("data") || (!host.isEmpty() && host.equals(host(document))));
    return preview ? "INVENTORY_PREVIEW" : "UNEXPECTED";
  }

  private static String host(String value) {
    try {
      String h = new URI(value).getHost();
      return h == null ? "" : h;
    } catch (URISyntaxException e) {
      return "";
    }
  }
}
