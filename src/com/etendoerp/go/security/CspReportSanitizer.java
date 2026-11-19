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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
  private static final Set<String> EXTENSION_SCHEMES = Set.of("chrome-extension", "moz-extension",
      "safari-extension", "safari-web-extension", "ms-browser-extension");
  private static final Set<String> KEYWORDS = Set.of("inline", "eval", "self", "wasm-eval",
      "trusted-types-policy", "trusted-types-sink");
  private static final Pattern TOKEN_SEGMENT = Pattern.compile(
      "(?i)[0-9a-f-]{16,}|[A-Za-z0-9_+=.-]{24,}|[0-9]{6,}");

  private CspReportSanitizer() {
  }

  /** @return the sanitized report, or empty when the body is not a usable CSP report */
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
      out.put("classification", classify(blocked, directive));
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
    String value = raw.startsWith("blob:") ? raw.substring(5) : raw;
    if (raw.startsWith("data:")) {
      return "data";
    }
    if (raw.startsWith("blob:")) {
      return "blob";
    }
    try {
      URI u = new URI(value);
      String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
      if (!scheme.equals("http") && !scheme.equals("https")) {
        return EXTENSION_SCHEMES.contains(scheme) ? scheme + "://" : REDACTED;
      }
      StringBuilder sb = new StringBuilder(scheme).append("://").append(u.getHost());
      if (u.getPort() > 0) {
        sb.append(':').append(u.getPort());
      }
      for (String seg : (u.getRawPath() == null ? "" : u.getRawPath()).split("/", -1)) {
        if (!sb.toString().endsWith("/") || !seg.isEmpty()) {
          sb.append(seg.isEmpty() ? "" : "/" + redactSegment(seg));
        }
      }
      return cap(sb.toString());
    } catch (Exception e) {
      return REDACTED;
    }
  }

  private static String redactSegment(String seg) {
    return seg.contains("@") || seg.contains("%40") || TOKEN_SEGMENT.matcher(seg).matches()
        ? REDACTED : seg;
  }

  private static String cap(String s) {
    return s.length() <= MAX_FIELD_CHARS ? s : s.substring(0, MAX_FIELD_CHARS);
  }

  static String classify(String blocked, String directive) {
    if (blocked.equals("blob")) {
      return "INVENTORY_BLOB";
    }
    for (String scheme : EXTENSION_SCHEMES) {
      if (blocked.startsWith(scheme + "://")) {
        return "EXPECTED_EXTENSION";
      }
    }
    try {
      String host = new URI(blocked).getHost();
      if (host != null && TELEMETRY_HOSTS.contains(host)) {
        return "INVENTORY_TELEMETRY";
      }
    } catch (Exception e) {
      // not a URL: unexpected
    }
    return "UNEXPECTED";
  }
}
