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

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Measures wall-clock time for every Etendo GO HTTP request and emits a single
 * informational {@code log.error} when a request takes longer than
 * {@link #SLOW_REQUEST_THRESHOLD_SECONDS}. The log line identifies the HTTP method and
 * the full URL (with query string) so slow endpoints can be spotted without enabling
 * per-class debug logging.
 *
 * <p>It is purely observational: the request always proceeds down the chain unchanged
 * and the timing is taken in a {@code finally} block, so even failed requests are
 * reported. Mapped to {@code /*} so it covers every endpoint served by the webapp
 * (all Etendo GO servlets and any other request reaching this context).
 */
@WebFilter(urlPatterns = "/*")
public class RequestTimingFilter implements Filter {

  private static final Logger log = LogManager.getLogger(RequestTimingFilter.class);

  /**
   * Slow-request threshold, expressed in SECONDS. This is the single knob to tune: set it
   * to the number of seconds beyond which a request is considered slow. Kept above the
   * latency of normal CRUD calls so only genuinely slow requests (e.g. onboarding) show up.
   */
  private static final long SLOW_REQUEST_THRESHOLD_SECONDS = 10L;

  private final long thresholdMillis;

  /** Production constructor used by the servlet container (no-arg). */
  public RequestTimingFilter() {
    this(SLOW_REQUEST_THRESHOLD_SECONDS * 1000L);
  }

  /** Threshold-injectable variant (package-private, milliseconds) so tests need not wait. */
  RequestTimingFilter(long thresholdMillis) {
    this.thresholdMillis = thresholdMillis;
  }

  @Override
  public void init(FilterConfig filterConfig) throws ServletException {
    log.info("RequestTimingFilter initialized (slow-request threshold = {} s)",
        thresholdMillis / 1000L);
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    long startNanos = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
      if (elapsedMillis > thresholdMillis && request instanceof HttpServletRequest) {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        log.error("Slow Etendo GO request: {} {} took {} ms (threshold {} ms)",
            httpReq.getMethod(), buildFullUrl(httpReq), elapsedMillis, thresholdMillis);
      }
    }
  }

  @Override
  public void destroy() {
    // no-op
  }

  private static String buildFullUrl(HttpServletRequest httpReq) {
    String uri = httpReq.getRequestURI();
    String query = httpReq.getQueryString();
    return query == null ? uri : uri + "?" + query;
  }
}
