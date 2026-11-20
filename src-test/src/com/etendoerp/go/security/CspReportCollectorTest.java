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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etendoerp.go.usageevents.LogCapture;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end behavior of the CSP collector: sanitized-only output, size and rate limits, no echo
 * of rejected bodies, and an always-204 servlet.
 *
 * @covers com.etendoerp.go.security.CspReportCollector
 * @covers com.etendoerp.go.security.CspReportRateLimiter
 * @covers com.etendoerp.go.security.CspReportServlet
 */
class CspReportCollectorTest {

  private static final String SENSITIVE = "{\"csp-report\":{\"document-uri\":"
      + "\"https://go.test/a?code=OAUTHCODE\",\"blocked-uri\":\"https://evil.test/jane@corp.com/x"
      + "?access_token=TOKEN123\",\"effective-directive\":\"connect-src\","
      + "\"script-sample\":\"jane@corp.com secret\",\"referrer\":\"https://r.test/?s=1\"}}";

  private final AtomicLong clock = new AtomicLong();

  @BeforeEach
  void infoLevel() {
    Configurator.setLevel(CspReportCollector.class.getName(), Level.INFO);
  }

  private CspReportCollector collector(int perClient, int global) {
    return new CspReportCollector(new CspReportRateLimiter(perClient, global, 1000L, clock::get));
  }

  private static String all(List<String> lines) {
    return lines.stream().collect(Collectors.joining("\n"));
  }

  @Test
  void logsOnlySanitizedFieldsAndCountsByClassification() {
    CspReportCollector c = collector(10, 100);
    try (LogCapture cap = LogCapture.of(CspReportCollector.class)) {
      c.collect("1.1.1.1", new StringReader(SENSITIVE));
      String logged = all(cap.messages(Level.INFO)) + all(cap.messages(Level.WARN));
      assertTrue(logged.replace("\\", "").contains("https://evil.test/[redacted]/x"));
      for (String secret : new String[] {"TOKEN123", "OAUTHCODE", "jane", "secret", "r.test"}) {
        assertFalse(logged.contains(secret), secret);
      }
    }
    assertEquals(1, c.total("UNEXPECTED"));
  }

  @Test
  void oversizedBodyIsDroppedWithoutLoggingIt() {
    CspReportCollector c = collector(10, 100);
    String big = "{\"csp-report\":{\"blocked-uri\":\"TOKEN123"
        + "a".repeat(CspReportSanitizer.MAX_BODY_CHARS) + "\"}}";
    try (LogCapture cap = LogCapture.of(CspReportCollector.class)) {
      c.collect("1.1.1.1", new StringReader(big));
      assertFalse(all(cap.messages(Level.WARN)).contains("TOKEN123"));
    }
    assertEquals(1, c.total("OVERSIZED"));
  }

  @Test
  void malformedBodyIsNotEchoedInLogs() {
    CspReportCollector c = collector(10, 100);
    try (LogCapture cap = LogCapture.of(CspReportCollector.class)) {
      c.collect("1.1.1.1", new StringReader("password=TOKEN123 jane@corp.com {"));
      assertFalse(all(cap.messages(Level.WARN)).contains("TOKEN123"));
      assertFalse(all(cap.messages(Level.WARN)).contains("jane"));
    }
    assertEquals(1, c.total("MALFORMED"));
  }

  @Test
  void rateLimitsPerClientThenGlobally() {
    CspReportCollector c = collector(2, 3);
    for (int i = 0; i < 3; i++) {
      c.collect("a", new StringReader(SENSITIVE));
    }
    assertEquals(2, c.total("UNEXPECTED"));
    assertEquals(1, c.total("RATE_LIMITED"));
    c.collect("b", new StringReader(SENSITIVE));
    assertEquals(3, c.total("UNEXPECTED"));
    c.collect("c", new StringReader(SENSITIVE));
    assertEquals(3, c.total("UNEXPECTED"));
    assertEquals(2, c.total("RATE_LIMITED"));
    clock.set(1000L);
    c.collect("c", new StringReader(SENSITIVE));
    assertEquals(4, c.total("UNEXPECTED"));
  }

  @Test
  void readBoundedStopsAtTheCap() throws IOException {
    assertNull(CspReportCollector.readBounded(new StringReader("abcdef"), 5));
    assertEquals("abcde", CspReportCollector.readBounded(new StringReader("abcde"), 5));
  }

  @Test
  void unreadableBodyIsCountedNotThrown() {
    CspReportCollector c = collector(10, 100);
    Reader broken = new Reader() {
      @Override
      public int read(char[] b, int o, int l) throws IOException {
        throw new IOException("boom TOKEN123");
      }

      @Override
      public void close() {
        // nothing to release: the reader never opens a resource
      }
    };
    c.collect("a", broken);
    assertEquals(1, c.total("READ_ERROR"));
  }

  @Test
  void servletAlwaysAnswers204() throws Exception {
    CspReportServlet servlet = new CspReportServlet();
    for (String body : new String[] {SENSITIVE, "garbage", ""}) {
      HttpServletRequest req = mock(HttpServletRequest.class);
      HttpServletResponse resp = mock(HttpServletResponse.class);
      when(req.getRemoteAddr()).thenReturn("9.9.9.9");
      when(req.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
      servlet.doPost(req, resp);
      verify(resp).setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(req.getReader()).thenThrow(new IOException("x"));
    servlet.doPost(req, resp);
    verify(resp).setStatus(HttpServletResponse.SC_NO_CONTENT);
  }
}
