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

package com.etendoerp.go.common;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

/**
 * ETP-5646 — correlates a first-party NEO request with the SPA's RUM view and the Java trace.
 *
 * <p>For a request that carries a valid {@link NeoFeatureContext#HEADER} from a trusted origin
 * (same origin, or an origin allowlisted by {@link CorsUtils}), it opens the per-request logging
 * context ({@link NeoRequestLogContext}) and writes ONE structured line when the request ends:
 * the event {@code neo_request} with the feature key, the authenticated opaque user and tenant
 * ids, the request id, a coarse operation ({@code read}/{@code write}), the outcome, the latency
 * and the trace ids. Requests without a feature context are not logged here (their behaviour is
 * unchanged); an untrusted origin's header is ignored.</p>
 *
 * <p>The header is attribution only: user and tenant are bound by the authentication, never read
 * from the request. The line carries no path, query, payload, name, e-mail or secret.</p>
 *
 * <p>Outcomes: {@code success} (status below 400), {@code rejected} (4xx), {@code error} (5xx)
 * and {@code rollback} (an exception left the chain; the DAL request filter rolls the
 * transaction back). This filter observes the response only and never claims a business commit
 * on its own: the authoritative committed count of a business operation is its own telemetry
 * event (see docs/feature-usage-and-correlation.md).</p>
 */
@WebFilter(urlPatterns = "/*")
public class NeoRequestContextFilter implements Filter {

  /** Logger of the structured line; the Datadog pipeline matches on this name. */
  static final String LOGGER_NAME = "com.etendoerp.go.telemetry.NeoRequest";
  static final String REQUEST_ID_HEADER = "X-Request-Id";
  static final String EVENT = "neo_request";

  private static final Logger log = LogManager.getLogger(LOGGER_NAME);
  private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
  // W3C trace context: version-traceid-spanid-flags.
  private static final Pattern TRACEPARENT =
      Pattern.compile("^00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
  private static final Pattern SAFE_TAG = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    // no-op
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
      chain.doFilter(request, response);
      return;
    }
    HttpServletRequest http = (HttpServletRequest) request;
    HttpServletResponse httpResponse = (HttpServletResponse) response;
    String feature = trustedFeature(http);
    if (feature == null) {
      chain.doFilter(request, response);
      return;
    }

    long startNanos = System.nanoTime();
    NeoRequestLogContext.clear();
    NeoRequestLogContext.put(NeoRequestLogContext.REQUEST_ID, requestId(http));
    NeoRequestLogContext.put(NeoRequestLogContext.FEATURE_KEY, feature);
    NeoRequestLogContext.put(NeoRequestLogContext.OPERATION,
        isRead(http.getMethod()) ? "read" : "write");
    NeoRequestLogContext.put(NeoRequestLogContext.FRONTEND_TRACE_ID,
        frontendTraceId(http.getHeader("traceparent")));
    // The chain either returns (completed) or something left it: an exception, which the DAL
    // request filter turns into a rollback. No catch is needed to tell them apart.
    boolean completed = false;
    try {
      chain.doFilter(request, response);
      completed = true;
    } finally {
      finish(completed, httpResponse.getStatus(), startNanos);
    }
  }

  /** Logs the one outcome line and always clears the per-request context. */
  private static void finish(boolean completed, int status, long startNanos) {
    try {
      String outcome = completed ? outcomeOf(status) : "rollback";
      logOutcome(outcome, status, (System.nanoTime() - startNanos) / 1_000_000L);
    } finally {
      NeoRequestLogContext.clear();
    }
  }

  @Override
  public void destroy() {
    // no-op
  }

  /** The validated feature key, only when the request comes from a trusted origin. */
  static String trustedFeature(HttpServletRequest request) {
    String raw = StringUtils.trimToNull(request.getHeader(NeoFeatureContext.HEADER));
    if (raw == null) {
      return null;
    }
    String origin = StringUtils.trimToNull(request.getHeader("Origin"));
    if (origin != null && !CorsUtils.isAllowedOrigin(request, origin)) {
      return null;
    }
    return NeoFeatureContext.validate(raw);
  }

  static String requestId(HttpServletRequest request) {
    String supplied = StringUtils.trimToNull(request.getHeader(REQUEST_ID_HEADER));
    return supplied != null && REQUEST_ID.matcher(supplied).matches()
        ? supplied : UUID.randomUUID().toString();
  }

  static String frontendTraceId(String traceparent) {
    if (traceparent == null) {
      return null;
    }
    Matcher matcher = TRACEPARENT.matcher(traceparent.trim());
    return matcher.matches() ? matcher.group(1) : null;
  }

  static String outcomeOf(int status) {
    if (status >= 500) {
      return "error";
    }
    return status >= 400 ? "rejected" : "success";
  }

  private static boolean isRead(String method) {
    return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
        || "OPTIONS".equalsIgnoreCase(method);
  }

  private static void logOutcome(String outcome, int status, long latencyMs) {
    StringBuilder line = new StringBuilder("{");
    field(line, "event", EVENT);
    field(line, "feature_key", ThreadContext.get(NeoRequestLogContext.FEATURE_KEY));
    field(line, "ad_user_id", ThreadContext.get(NeoRequestLogContext.AD_USER_ID));
    field(line, "ad_client_id", ThreadContext.get(NeoRequestLogContext.AD_CLIENT_ID));
    field(line, "request_id", ThreadContext.get(NeoRequestLogContext.REQUEST_ID));
    field(line, "operation", ThreadContext.get(NeoRequestLogContext.OPERATION));
    field(line, "outcome", outcome);
    // Datadog Java agent keys, copied as strings (64-bit ids overflow a JSON number).
    field(line, "dd.trace_id", safeTag(ThreadContext.get("dd.trace_id")));
    field(line, "dd.span_id", safeTag(ThreadContext.get("dd.span_id")));
    field(line, "frontend_trace_id", ThreadContext.get(NeoRequestLogContext.FRONTEND_TRACE_ID));
    field(line, "service", safeTag(System.getenv("DD_SERVICE")));
    field(line, "env", safeTag(System.getenv("DD_ENV")));
    field(line, "version", safeTag(System.getenv("DD_VERSION")));
    line.append("\"http_status\":").append(status).append(",\"latency_ms\":").append(latencyMs)
        .append('}');
    log.info(line.toString());
  }

  /** Appends {@code "key":"value",} for a non-empty value; every value is a bounded safe token. */
  private static void field(StringBuilder line, String key, String value) {
    if (StringUtils.isNotEmpty(value)) {
      line.append('"').append(key).append("\":\"").append(value).append("\",");
    }
  }

  private static String safeTag(String value) {
    return value != null && SAFE_TAG.matcher(value).matches() ? value : null;
  }
}
