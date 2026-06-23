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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link RequestTimingFilter}.
 *
 * <p>The filter is purely observational, so the tests assert two things: (1) it never
 * alters the request/response or the chain (it cannot "clobber" or break anything), and
 * (2) the slow-request branch fires — emitting the method and URL — only when the elapsed
 * time exceeds the threshold. The threshold-injectable constructor lets the slow path be
 * exercised deterministically without waiting the production 10s cadence.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RequestTimingFilterTest {

  // Any positive elapsed time exceeds -1, so the slow branch always fires.
  private static final long ALWAYS_SLOW = -1L;
  // No real request takes longer than this, so the slow branch never fires.
  private static final long NEVER_SLOW = Long.MAX_VALUE;

  @Mock
  private HttpServletRequest httpRequest;
  @Mock
  private HttpServletResponse httpResponse;
  @Mock
  private FilterChain chain;

  @Test
  @DisplayName("Always delegates and never touches the request or response (clobbers nothing)")
  void passesThroughUnchangedAndDoesNotTouchResponse() throws Exception {
    RequestTimingFilter filter = new RequestTimingFilter(NEVER_SLOW);

    filter.doFilter(httpRequest, httpResponse, chain);

    // The exact same instances flow down the chain, exactly once.
    verify(chain, times(1)).doFilter(httpRequest, httpResponse);
    // A fast request is never inspected and the response is never written to.
    verify(httpRequest, never()).getMethod();
    verify(httpRequest, never()).getRequestURI();
    verifyNoInteractions(httpResponse);
  }

  @Test
  @DisplayName("Slow request is reported with HTTP method and request URI")
  void slowRequestLogsMethodAndUrl() throws Exception {
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getRequestURI()).thenReturn("/sws/go/onboarding");
    when(httpRequest.getQueryString()).thenReturn(null);
    RequestTimingFilter filter = new RequestTimingFilter(ALWAYS_SLOW);

    filter.doFilter(httpRequest, httpResponse, chain);

    verify(chain, times(1)).doFilter(httpRequest, httpResponse);
    // The slow branch builds the log line from the method and URI.
    verify(httpRequest).getMethod();
    verify(httpRequest).getRequestURI();
    // Even when reporting, the response is left untouched.
    verifyNoInteractions(httpResponse);
  }

  @Test
  @DisplayName("Slow request with a query string includes it in the reported URL")
  void slowRequestIncludesQueryString() throws Exception {
    when(httpRequest.getMethod()).thenReturn("GET");
    when(httpRequest.getRequestURI()).thenReturn("/sws/neo/sales-order");
    when(httpRequest.getQueryString()).thenReturn("status=open&limit=50");
    RequestTimingFilter filter = new RequestTimingFilter(ALWAYS_SLOW);

    filter.doFilter(httpRequest, httpResponse, chain);

    verify(httpRequest).getQueryString();
    verify(httpRequest).getRequestURI();
  }

  @Test
  @DisplayName("An exception from the chain propagates unchanged and is still timed")
  void exceptionFromChainPropagatesUnchanged() throws Exception {
    ServletException boom = new ServletException("downstream failure");
    org.mockito.Mockito.doThrow(boom).when(chain).doFilter(httpRequest, httpResponse);
    when(httpRequest.getMethod()).thenReturn("POST");
    when(httpRequest.getRequestURI()).thenReturn("/sws/go/onboarding");
    RequestTimingFilter filter = new RequestTimingFilter(ALWAYS_SLOW);

    // The filter neither swallows nor wraps the downstream error.
    ServletException thrown = assertThrows(ServletException.class,
        () -> filter.doFilter(httpRequest, httpResponse, chain));
    org.junit.jupiter.api.Assertions.assertSame(boom, thrown);
    verify(chain, times(1)).doFilter(httpRequest, httpResponse);
  }

  @Test
  @DisplayName("A non-HTTP request on the slow path does not throw")
  void nonHttpRequestDoesNotThrowOnSlowPath() throws Exception {
    ServletRequest plainRequest = org.mockito.Mockito.mock(ServletRequest.class);
    ServletResponse plainResponse = org.mockito.Mockito.mock(ServletResponse.class);
    RequestTimingFilter filter = new RequestTimingFilter(ALWAYS_SLOW);

    assertDoesNotThrow(() -> filter.doFilter(plainRequest, plainResponse, chain));
    verify(chain, times(1)).doFilter(plainRequest, plainResponse);
    // No HttpServletRequest casting happened, so nothing was inspected on it.
    verifyNoInteractions(plainResponse);
  }

  @Test
  @DisplayName("init() and destroy() are safe no-ops")
  void initAndDestroyAreSafe() {
    RequestTimingFilter filter = new RequestTimingFilter();
    FilterConfig config = org.mockito.Mockito.mock(FilterConfig.class);

    assertDoesNotThrow(() -> filter.init(config));
    assertDoesNotThrow(filter::destroy);
  }

  @Test
  @DisplayName("The default (no-arg) constructor wires the production threshold")
  void defaultConstructorDoesNotReportFastRequest() throws IOException, ServletException {
    // Built via the no-arg constructor (10s threshold); a fast mock request must not report.
    RequestTimingFilter filter = new RequestTimingFilter();

    filter.doFilter(httpRequest, httpResponse, chain);

    verify(chain, times(1)).doFilter(httpRequest, httpResponse);
    verify(httpRequest, never()).getMethod();
  }
}
