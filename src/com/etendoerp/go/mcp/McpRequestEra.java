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

package com.etendoerp.go.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.GoRuntimeProperties;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * Decides which MCP era a request belongs to, and whether a modern request is well formed
 * (ETP-5640, design {@code docs/plans/2026-10-09-etp-5640-mcp-dual-era-design.md} §2).
 *
 * <p>The body is the source of truth; HTTP headers only mirror it. A request is <b>modern</b>
 * (2026-07-28, stateless) when its {@code params._meta} carries
 * {@value #META_PROTOCOL_VERSION}, when its method is {@value #SERVER_DISCOVER}, or when its
 * {@value McpProtocolVersion#HEADER} header names a modern revision. {@code initialize} is always
 * <b>legacy</b>, and so is anything without a modern marker — the ETP-5639 path, unchanged.</p>
 *
 * <p>A modern request that disagrees with itself — a header that contradicts the body, a version
 * we do not serve, a malformed {@code _meta} — is refused with the spec's error. A modern request
 * that merely <i>omits</i> a mirror header or {@code clientCapabilities} is served, with an issue
 * reported for a WARN line, unless {@code strict}: refusing it would hand the client a recognised
 * modern error, so it would retry instead of falling back, and fail forever (design §2.2).</p>
 *
 * <p>{@link #classify} is pure: no servlet, DAL or {@code OBContext}. Every branch is on protocol
 * structure, never on a spec or entity. The two switches around it are read separately:
 * {@link #modernEnabled()} (the {@link GoFeatureFlags#FLAG_MCP_MODERN_ERA_DISABLED} kill
 * switch) and {@link #strict()} ({@value #PROP_STRICT}).</p>
 */
final class McpRequestEra {

  /** The MCP era a request is served under. */
  enum Era {
    /** {@code initialize}-based revisions (2024-11-05 … 2025-11-25). */
    LEGACY,
    /** Stateless revisions (2026-07-28 onwards). */
    MODERN
  }

  /** {@code params._meta} key carrying the request's protocol version (modern). */
  static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
  /** {@code params._meta} key carrying the client's capabilities (modern). */
  static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
  /** Header mirroring the JSON-RPC {@code method} (Streamable HTTP, 2026-07-28). */
  static final String HEADER_METHOD = "Mcp-Method";
  /** Header mirroring {@code params.name} / {@code params.uri} (Streamable HTTP, 2026-07-28). */
  static final String HEADER_NAME = "Mcp-Name";
  /** The modern discovery method; it exists in no legacy revision. */
  static final String SERVER_DISCOVER = "server/discover";

  /** JSON-RPC 2.0: invalid method parameters — also a malformed modern {@code _meta}. */
  static final int INVALID_PARAMS = -32602;
  /** MCP 2026-07-28: a header does not match the body, or a required header is missing. */
  static final int HEADER_MISMATCH = -32020;
  /** MCP 2026-07-28: the requested protocol version is not served. */
  static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

  /**
   * Openbravo/JVM property: {@code true} refuses a modern request that omits a mirror header or
   * {@code clientCapabilities}, instead of serving it with a WARN. Default {@code false} (design
   * §2.2).
   */
  static final String PROP_STRICT = "mcp.modern.strict";
  /** Environment-variable spelling of {@value #PROP_STRICT}. */
  static final String ENV_STRICT = "ETGO_MCP_MODERN_STRICT";

  /**
   * The kill switch is environment level: no targeting key, no attributes (see
   * {@link GoFeatureFlags#FLAG_MCP_MODERN_ERA_DISABLED}). Shared, so nothing is built per request.
   */
  private static final FeatureFlagContext ENVIRONMENT = FeatureFlagContext.forAccount(null);

  /** Methods that select legacy semantics whatever else the request carries. */
  private static final Set<String> LEGACY_HANDSHAKE =
      Set.of("initialize", "initialized", "notifications/initialized");

  private static final String BASE64_PREFIX = "=?base64?";
  private static final String BASE64_SUFFIX = "?=";
  /** Control characters and Unicode line/paragraph separators: what could forge a log line. */
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");
  /** Bound on how much of an unexpected value reaches a message or a log line. */
  private static final int MAX_ECHOED_VALUE = 60;

  private McpRequestEra() {
  }

  /**
   * The mirror headers of one request, as received (raw, possibly Base64-sentinel encoded).
   *
   * @param protocolVersion the {@value McpProtocolVersion#HEADER} header, may be {@code null}
   * @param method          the {@value #HEADER_METHOD} header, may be {@code null}
   * @param name            the {@value #HEADER_NAME} header, may be {@code null}
   */
  record Headers(String protocolVersion, String method, String name) {
  }

  /**
   * Why a modern request is refused, as the servlet answers it.
   *
   * @param httpStatus the HTTP status
   * @param code       the JSON-RPC error code
   * @param message    the error message
   * @param data       the error {@code data}, or {@code null}
   */
  record Refusal(int httpStatus, int code, String message, JSONObject data) {
  }

  /**
   * The decision for one request.
   *
   * @param era             the era to serve it under
   * @param protocolVersion the modern version it declared, or {@code null} for a legacy request
   * @param refusal         why it is refused, or {@code null} when it is served
   * @param issues          what a served modern request left out, one WARN line each
   */
  record Classification(Era era, String protocolVersion, Refusal refusal, List<String> issues) {

    boolean isModern() {
      return era == Era.MODERN;
    }

    static Classification legacy() {
      return new Classification(Era.LEGACY, null, null, List.of());
    }
  }

  /**
   * Classify one HTTP request, or call it legacy outright while the
   * {@link GoFeatureFlags#FLAG_MCP_MODERN_ERA_DISABLED} kill switch is on — exactly how the servlet
   * behaved before the modern era existed.
   *
   * @param request the HTTP request, read for its mirror headers only
   * @param method  the JSON-RPC method
   * @param params  the JSON-RPC {@code params}, may be {@code null}
   * @return the decision, never {@code null}
   */
  static Classification forRequest(HttpServletRequest request, String method,
      JSONObject params) {
    if (!modernEnabled()) {
      return Classification.legacy();
    }
    return classify(method, params,
        new Headers(request.getHeader(McpProtocolVersion.HEADER), request.getHeader(HEADER_METHOD),
            request.getHeader(HEADER_NAME)),
        strict());
  }

  /**
   * A client-supplied value made safe to echo into a log line or an error message: every control
   * character (CR, LF, TAB, …) becomes {@code ?}, so a header or a {@code _meta} field cannot forge
   * a log line, and the result is bounded.
   *
   * @param value the value, may be {@code null}
   * @return the printable value, or {@code null}
   */
  static String printable(String value) {
    if (value == null) {
      return null;
    }
    return StringUtils.abbreviate(CONTROL.matcher(value).replaceAll("?"), MAX_ECHOED_VALUE);
  }

  /**
   * Classify one request.
   *
   * @param method  the JSON-RPC method, may be {@code null}
   * @param params  the JSON-RPC {@code params}, may be {@code null}
   * @param headers the mirror headers
   * @param strict  whether a missing mirror header or {@code clientCapabilities} refuses the
   *                request instead of being reported
   * @return the decision, never {@code null}
   */
  static Classification classify(String method, JSONObject params, Headers headers,
      boolean strict) {
    if (method != null && LEGACY_HANDSHAKE.contains(method)) {
      return Classification.legacy();
    }
    JSONObject meta = params != null ? params.optJSONObject("_meta") : null;
    Object declared = meta != null ? meta.opt(META_PROTOCOL_VERSION) : null;
    String headerVersion = StringUtils.trimToNull(headers.protocolVersion());
    boolean modern = declared != null || SERVER_DISCOVER.equals(method)
        || (headerVersion != null
            && McpProtocolVersion.MODERN_SUPPORTED.contains(headerVersion));
    if (!modern) {
      return Classification.legacy();
    }
    return new ModernCheck(method, params, meta, declared, headers, headerVersion).run(strict);
  }

  /**
   * The protocol version a request declares in {@code params._meta}, whatever the era it is served
   * under — telemetry reads it even with the kill switch on.
   *
   * @param params the request's {@code params}, may be {@code null}
   * @return the declared version, or {@code null} when the request declares none as a string
   */
  static String declaredVersion(JSONObject params) {
    JSONObject meta = params != null ? params.optJSONObject("_meta") : null;
    Object declared = meta != null ? meta.opt(META_PROTOCOL_VERSION) : null;
    return declared instanceof String ? StringUtils.trimToNull((String) declared) : null;
  }

  /**
   * Whether the modern era is served. {@code false} only when the
   * {@link GoFeatureFlags#FLAG_MCP_MODERN_ERA_DISABLED} kill switch is positively on; a missing
   * flag or an unreachable control plane keeps the server dual-era.
   *
   * @return {@code true} unless the kill switch is on
   */
  static boolean modernEnabled() {
    return !GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_MCP_MODERN_ERA_DISABLED, ENVIRONMENT);
  }

  /** @return whether {@value #PROP_STRICT} is on (default {@code false}) */
  static boolean strict() {
    return GoRuntimeProperties.readBoolean(PROP_STRICT, ENV_STRICT, false);
  }

  /**
   * Decode a mirror-header value written in the Base64 sentinel form {@code =?base64?…?=}
   * (Streamable HTTP, <i>Value Encoding</i>); any other value is returned as is.
   *
   * @param value the raw header value, may be {@code null}
   * @return the decoded value
   * @throws IllegalArgumentException when the sentinel wraps something that is not Base64
   */
  static String decodeHeaderValue(String value) {
    if (value == null || value.length() < BASE64_PREFIX.length() + BASE64_SUFFIX.length()
        || !value.startsWith(BASE64_PREFIX) || !value.endsWith(BASE64_SUFFIX)) {
      return value;
    }
    String encoded = value.substring(BASE64_PREFIX.length(),
        value.length() - BASE64_SUFFIX.length());
    return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
  }

  /** The validation of one modern request, in the order the design's decision table reads. */
  private static final class ModernCheck {

    private final String method;
    private final JSONObject params;
    private final JSONObject meta;
    private final Object declared;
    private final Headers headers;
    private final String headerVersion;
    /** Missing pieces: served with a WARN, or refused when strict. First one decides the code. */
    private final List<Refusal> missing = new ArrayList<>();

    ModernCheck(String method, JSONObject params, JSONObject meta, Object declared,
        Headers headers, String headerVersion) {
      this.method = method;
      this.params = params;
      this.meta = meta;
      this.declared = declared;
      this.headers = headers;
      this.headerVersion = headerVersion;
    }

    Classification run(boolean strict) {
      Refusal refusal = checkVersion();
      if (refusal == null) {
        refusal = checkMirror(HEADER_METHOD, headers.method(), method, true);
      }
      if (refusal == null) {
        String name = nameInBody();
        refusal = checkMirror(HEADER_NAME, headers.name(), name, name != null);
      }
      if (refusal == null && meta != null
          && !(meta.opt(META_CLIENT_CAPABILITIES) instanceof JSONObject)) {
        missing.add(new Refusal(HttpServletResponse.SC_BAD_REQUEST, INVALID_PARAMS,
            "Missing params._meta[\"" + META_CLIENT_CAPABILITIES + "\"]", null));
      }
      if (refusal == null && strict && !missing.isEmpty()) {
        refusal = missing.get(0);
      }
      String version = declared instanceof String ? (String) declared
          : McpProtocolVersion.MODERN_LATEST;
      List<String> issues = new ArrayList<>();
      if (refusal == null) {
        for (Refusal gap : missing) {
          issues.add(gap.message());
        }
      }
      return new Classification(Era.MODERN, version, refusal,
          Collections.unmodifiableList(issues));
    }

    /** The declared version: present, a string, mirrored by the header, and served. */
    private Refusal checkVersion() {
      if (declared == null) {
        missing.add(new Refusal(HttpServletResponse.SC_BAD_REQUEST, INVALID_PARAMS,
            "Missing params._meta[\"" + META_PROTOCOL_VERSION + "\"]", null));
        return null;
      }
      if (!(declared instanceof String) || StringUtils.isBlank((String) declared)) {
        return new Refusal(HttpServletResponse.SC_BAD_REQUEST, INVALID_PARAMS,
            "params._meta[\"" + META_PROTOCOL_VERSION + "\"] must be a non-empty string", null);
      }
      String version = (String) declared;
      if (headerVersion == null) {
        missing.add(new Refusal(HttpServletResponse.SC_BAD_REQUEST, HEADER_MISMATCH,
            "Missing " + McpProtocolVersion.HEADER + " header", null));
      } else if (!headerVersion.equals(version)) {
        return mismatch(McpProtocolVersion.HEADER, headerVersion, version);
      }
      if (!McpProtocolVersion.MODERN_SUPPORTED.contains(version)) {
        return unsupported(version);
      }
      return null;
    }

    /**
     * One mirror header against its body value: a mismatch is refused, an absence is recorded.
     *
     * @param required whether the header is required for this request
     */
    private Refusal checkMirror(String header, String rawValue, String bodyValue,
        boolean required) {
      if (rawValue == null) {
        if (required) {
          missing.add(new Refusal(HttpServletResponse.SC_BAD_REQUEST, HEADER_MISMATCH,
              "Missing " + header + " header", null));
        }
        return null;
      }
      if (bodyValue == null) {
        // Nothing in the body to mirror (e.g. Mcp-Name on tools/list): ignored, as the spec does
        // not ask for it.
        return null;
      }
      String value;
      try {
        value = decodeHeaderValue(rawValue);
      } catch (IllegalArgumentException e) {
        return new Refusal(HttpServletResponse.SC_BAD_REQUEST, HEADER_MISMATCH,
            "Header mismatch: " + header + " header is not valid Base64", null);
      }
      return Objects.equals(value, bodyValue) ? null : mismatch(header, value, bodyValue);
    }

    /** {@code params.name} on {@code tools/call}, {@code params.uri} on {@code resources/read}. */
    private String nameInBody() {
      if (params == null) {
        return null;
      }
      if ("tools/call".equals(method)) {
        return StringUtils.trimToNull(params.optString("name", null));
      }
      if ("resources/read".equals(method)) {
        return StringUtils.trimToNull(params.optString("uri", null));
      }
      return null;
    }

    private static Refusal mismatch(String header, String headerValue, String bodyValue) {
      return new Refusal(HttpServletResponse.SC_BAD_REQUEST, HEADER_MISMATCH,
          "Header mismatch: " + header + " header value '" + printable(headerValue)
              + "' does not match body value '" + printable(bodyValue) + "'",
          null);
    }

    private static Refusal unsupported(String requested) {
      JSONObject data = new JSONObject();
      try {
        data.put("supported", new JSONArray(McpProtocolVersion.ALL_SUPPORTED));
        data.put("requested", printable(requested));
      } catch (JSONException e) {
        // Cannot happen with string values; the code alone still identifies the error.
        data = null;
      }
      return new Refusal(HttpServletResponse.SC_BAD_REQUEST, UNSUPPORTED_PROTOCOL_VERSION,
          "Unsupported protocol version", data);
    }
  }
}
