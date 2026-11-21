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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ETP-5646 — {@link NeoRequestContextFilter}, {@link NeoRequestLogContext} and
 * {@link NeoFeatureContext}: menu / direct-URL requests, missing context, concurrent requests,
 * failure and rollback, MDC cleanup and trusted-origin restrictions.
 */
class NeoRequestContextFilterTest {

  private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String TRACEPARENT = "00-" + TRACE + "-00f067aa0ba902b7-01";

  private final List<String> lines = new ArrayList<>();
  private AbstractAppender appender;
  private org.apache.logging.log4j.core.Logger logger;

  @BeforeEach
  void captureLog() {
    ThreadContext.clearAll();
    logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(
        NeoRequestContextFilter.LOGGER_NAME);
    appender = new AbstractAppender("neo-request-test", null, null, true, Property.EMPTY_ARRAY) {
      @Override
      public void append(LogEvent event) {
        synchronized (lines) {
          lines.add(event.getMessage().getFormattedMessage());
        }
      }
    };
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(org.apache.logging.log4j.Level.INFO);
  }

  @AfterEach
  void release() {
    logger.removeAppender(appender);
    appender.stop();
    ThreadContext.clearAll();
    ((LoggerContext) LogManager.getContext(false)).updateLoggers();
  }

  private static HttpServletRequest request(String method, String feature, String origin) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn(method);
    when(request.getHeader(NeoFeatureContext.HEADER)).thenReturn(feature);
    when(request.getHeader("Origin")).thenReturn(origin);
    when(request.getHeader("traceparent")).thenReturn(TRACEPARENT);
    when(request.getRequestURL()).thenReturn(new StringBuffer("https://go.example.com/sws/go/x"));
    when(request.getScheme()).thenReturn("https");
    when(request.getServerName()).thenReturn("go.example.com");
    when(request.getServerPort()).thenReturn(443);
    return request;
  }

  private static HttpServletResponse response(int status) {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getStatus()).thenReturn(status);
    return response;
  }

  private String onlyLine() {
    assertEquals(1, lines.size(), lines.toString());
    return lines.get(0);
  }

  @Test
  @DisplayName("Valid feature from a same-origin request: one structured line, MDC cleared after")
  void menuNavigationIsLoggedOnceWithAuthenticatedIdentity() throws Exception {
    AtomicReference<String> featureInChain = new AtomicReference<>();
    FilterChain chain = (req, res) -> {
      featureInChain.set(ThreadContext.get(NeoRequestLogContext.FEATURE_KEY));
      NeoRequestLogContext.bindIdentity("100", "23C59575B9CF467C9620760EB255B389");
    };

    new NeoRequestContextFilter().doFilter(
        request("GET", "window.sales-order", "https://go.example.com"), response(200), chain);

    assertEquals("window.sales-order", featureInChain.get());
    String line = onlyLine();
    assertTrue(line.contains("\"event\":\"neo_request\""), line);
    assertTrue(line.contains("\"feature_key\":\"window.sales-order\""), line);
    assertTrue(line.contains("\"ad_user_id\":\"100\""), line);
    assertTrue(line.contains("\"ad_client_id\":\"23C59575B9CF467C9620760EB255B389\""), line);
    assertTrue(line.contains("\"operation\":\"read\""), line);
    assertTrue(line.contains("\"outcome\":\"success\""), line);
    assertTrue(line.contains("\"frontend_trace_id\":\"" + TRACE + "\""), line);
    assertTrue(line.contains("\"request_id\":\""), line);
    assertTrue(line.contains("\"latency_ms\":"), line);
    assertTrue(ThreadContext.isEmpty(), "MDC must be empty after the request");
  }

  @Test
  @DisplayName("A direct URL (no Origin) is a first-party request like a menu click")
  void directUrlWithoutOriginIsTrusted() throws Exception {
    new NeoRequestContextFilter().doFilter(
        request("POST", "custom.invoice-inbox", null), response(201), (req, res) -> { });

    String line = onlyLine();
    assertTrue(line.contains("\"feature_key\":\"custom.invoice-inbox\""), line);
    assertTrue(line.contains("\"operation\":\"write\""), line);
  }

  @Test
  @DisplayName("Missing context: the request passes through untouched and nothing is logged")
  void missingFeatureIsNotLoggedAndLeavesNoContext() throws Exception {
    AtomicReference<String> seen = new AtomicReference<>("unset");
    new NeoRequestContextFilter().doFilter(request("GET", null, null), response(200),
        (req, res) -> seen.set(ThreadContext.get(NeoRequestLogContext.FEATURE_KEY)));

    assertNull(seen.get());
    assertTrue(lines.isEmpty());
    assertTrue(ThreadContext.isEmpty());
  }

  @Test
  @DisplayName("Malformed or injected feature values are dropped, not repaired")
  void invalidFeatureIsIgnored() throws Exception {
    for (String bad : new String[] { "Window.Sales", "menu.x", "window.a b", "window.x\"}", "window." + "a".repeat(80),
        "window.sales-order\r\nX-Evil: 1" }) {
      new NeoRequestContextFilter().doFilter(request("GET", bad, null), response(200),
          (req, res) -> { });
    }
    assertTrue(lines.isEmpty(), lines.toString());
  }

  @Test
  @DisplayName("Trusted origins: an untrusted Origin cannot attribute a request to a feature")
  void untrustedOriginIsIgnored() throws Exception {
    new NeoRequestContextFilter().doFilter(
        request("GET", "window.sales-order", "https://evil.example"), response(200),
        (req, res) -> { });

    assertTrue(lines.isEmpty(), lines.toString());
    assertTrue(NeoRequestContextFilter.trustedFeature(
        request("GET", "window.sales-order", "http://localhost:3100")) != null);
  }

  @Test
  @DisplayName("A forged identity header never replaces the authenticated one")
  void identityComesOnlyFromAuthentication() throws Exception {
    HttpServletRequest request = request("GET", "window.sales-order", null);
    when(request.getHeader("X-Go-Account")).thenReturn("forged");
    when(request.getHeader("ad_user_id")).thenReturn("forged");

    new NeoRequestContextFilter().doFilter(request, response(200), (req, res) -> { });

    assertFalse(onlyLine().contains("forged"));
    assertFalse(onlyLine().contains("ad_user_id"));
  }

  @Test
  @DisplayName("bindIdentity outside a filtered request is a no-op and skips non-opaque ids")
  void bindIdentityIsBounded() throws Exception {
    NeoRequestLogContext.bindIdentity("100", "C");
    assertTrue(ThreadContext.isEmpty());

    new NeoRequestContextFilter().doFilter(request("GET", "window.a", null), response(200),
        (req, res) -> NeoRequestLogContext.bindIdentity("a@b.com", "x y"));
    String line = onlyLine();
    assertFalse(line.contains("ad_user_id"), line);
    assertFalse(line.contains("ad_client_id"), line);
  }

  @Test
  @DisplayName("Outcomes: 4xx is rejected, 5xx is error")
  void failureStatusesAreDistinguished() throws Exception {
    new NeoRequestContextFilter().doFilter(request("POST", "window.a", null), response(409),
        (req, res) -> { });
    new NeoRequestContextFilter().doFilter(request("POST", "window.a", null), response(500),
        (req, res) -> { });

    assertTrue(lines.get(0).contains("\"outcome\":\"rejected\""), lines.get(0));
    assertTrue(lines.get(1).contains("\"outcome\":\"error\""), lines.get(1));
    assertTrue(ThreadContext.isEmpty());
  }

  @Test
  @DisplayName("An exception is a rollback, is rethrown and still clears the MDC")
  void exceptionIsRollbackAndCleansUp() {
    FilterChain chain = (req, res) -> {
      NeoRequestLogContext.bindIdentity("100", "C1");
      throw new IllegalStateException("boom");
    };

    assertThrows(IllegalStateException.class, () -> new NeoRequestContextFilter().doFilter(
        request("PUT", "window.sales-order", null), response(200), chain));

    String line = onlyLine();
    assertTrue(line.contains("\"outcome\":\"rollback\""), line);
    assertFalse(line.contains("boom"), "no exception message in the log line");
    assertTrue(ThreadContext.isEmpty(), "MDC must be empty after a failed request");
  }

  @Test
  @DisplayName("A checked failure from the chain is also rolled back and cleaned")
  void checkedExceptionCleansUp() {
    FilterChain chain = (req, res) -> {
      throw new IOException("io");
    };
    assertThrows(IOException.class, () -> new NeoRequestContextFilter().doFilter(
        request("POST", "window.a", null), response(200), chain));
    assertTrue(lines.get(0).contains("\"outcome\":\"rollback\""));
    assertTrue(ThreadContext.isEmpty());
  }

  @Test
  @DisplayName("A pooled thread never carries one request's context into the next")
  void nothingLeaksToTheNextRequestOnTheSameThread() throws Exception {
    new NeoRequestContextFilter().doFilter(request("GET", "window.a", null), response(200),
        (req, res) -> NeoRequestLogContext.bindIdentity("100", "C1"));
    AtomicReference<String> next = new AtomicReference<>("unset");
    new NeoRequestContextFilter().doFilter(request("GET", "window.b", null), response(200),
        (req, res) -> next.set(ThreadContext.get(NeoRequestLogContext.AD_USER_ID)));

    assertNull(next.get());
    assertFalse(lines.get(1).contains("\"ad_user_id\""), lines.get(1));
  }

  @Test
  @DisplayName("Concurrent requests (several tabs) keep their own feature and request id")
  void concurrentRequestsAreIsolated() throws Exception {
    int tasks = 8;
    ExecutorService pool = Executors.newFixedThreadPool(tasks);
    CountDownLatch start = new CountDownLatch(1);
    List<Throwable> failures = new ArrayList<>();
    for (int i = 0; i < tasks; i++) {
      String feature = "window.tab-" + i;
      pool.submit(() -> {
        try {
          start.await();
          new NeoRequestContextFilter().doFilter(request("GET", feature, null), response(200),
              (req, res) -> {
                try {
                  Thread.sleep(5);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                assertEquals(feature, ThreadContext.get(NeoRequestLogContext.FEATURE_KEY));
              });
          assertTrue(ThreadContext.isEmpty());
        } catch (Throwable t) {
          synchronized (failures) {
            failures.add(t);
          }
        }
      });
    }
    start.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    assertTrue(failures.isEmpty(), failures.toString());
    assertEquals(tasks, lines.size());
    for (int i = 0; i < tasks; i++) {
      final String feature = "\"feature_key\":\"window.tab-" + i + "\"";
      assertEquals(1, lines.stream().filter(l -> l.contains(feature)).count());
    }
  }

  @Test
  @DisplayName("wrap() carries the context into async work and clears it afterwards")
  void asyncWorkIsWrappedAndCleaned() throws Exception {
    AtomicReference<String> inTask = new AtomicReference<>();
    AtomicReference<Boolean> emptyAfter = new AtomicReference<>();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    new NeoRequestContextFilter().doFilter(request("GET", "window.a", null), response(200),
        (req, res) -> {
          Runnable work = NeoRequestLogContext.wrap(
              () -> inTask.set(ThreadContext.get(NeoRequestLogContext.FEATURE_KEY)));
          try {
            pool.submit(work).get(5, TimeUnit.SECONDS);
            pool.submit(() -> emptyAfter.set(ThreadContext.isEmpty())).get(5, TimeUnit.SECONDS);
          } catch (Exception e) {
            throw new ServletException(e);
          }
        });
    pool.shutdownNow();

    assertEquals("window.a", inTask.get());
    assertEquals(Boolean.TRUE, emptyAfter.get());
  }

  @Test
  @DisplayName("wrap() clears the context even when the task throws")
  void wrappedTaskThatThrowsStillClears() {
    ThreadContext.put(NeoRequestLogContext.REQUEST_ID, "abcdefgh");
    Runnable wrapped = NeoRequestLogContext.wrap(() -> {
      throw new IllegalStateException("x");
    });
    assertThrows(IllegalStateException.class, wrapped::run);
    assertNull(ThreadContext.get(NeoRequestLogContext.REQUEST_ID));
  }

  @Test
  @DisplayName("A supplied request id is kept only when well formed; traceparent only when valid")
  void requestAndTraceIdsAreValidated() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-Request-Id")).thenReturn("req-12345678");
    assertEquals("req-12345678", NeoRequestContextFilter.requestId(request));
    when(request.getHeader("X-Request-Id")).thenReturn("bad id\r\n");
    assertNotNull(NeoRequestContextFilter.requestId(request));
    assertFalse(NeoRequestContextFilter.requestId(request).contains("bad"));

    assertEquals(TRACE, NeoRequestContextFilter.frontendTraceId(TRACEPARENT));
    assertNull(NeoRequestContextFilter.frontendTraceId("00-xyz"));
    assertNull(NeoRequestContextFilter.frontendTraceId(null));
  }

  @Test
  @DisplayName("Feature keys: only the four kinds, lower-case, bounded")
  void featureValidation() {
    assertEquals("report.tax-report", NeoFeatureContext.validate("report.tax-report"));
    assertNull(NeoFeatureContext.validate("window."));
    assertNull(NeoFeatureContext.validate(null));
    assertNull(NeoFeatureContext.validate("window.a_b"));
  }
}
