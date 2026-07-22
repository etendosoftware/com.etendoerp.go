/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;

import javax.servlet.DispatcherType;
import javax.servlet.http.HttpSession;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the no-op defaults exposed by {@link SyntheticServletRequestBase}.
 *
 * <p>The base is abstract but every method here is a pure constant/no-op default; a minimal
 * concrete subclass ({@link StubRequest}) satisfies the remaining {@code HttpServletRequest}
 * contract so the inherited defaults can be exercised without any servlet container.</p>
 */
class SyntheticServletRequestBaseTest {

  /** Minimal concrete subclass; only implements the methods the base leaves abstract. */
  private static final class StubRequest extends SyntheticServletRequestBase {
    @Override public Object getAttribute(String name) { return null; }
    @Override public Enumeration<String> getAttributeNames() { return Collections.emptyEnumeration(); }
    @Override public void setAttribute(String name, Object o) { /* no-op */ }
    @Override public void removeAttribute(String name) { /* no-op */ }
    @Override public String getParameter(String name) { return null; }
    @Override public Enumeration<String> getParameterNames() { return Collections.emptyEnumeration(); }
    @Override public String[] getParameterValues(String name) { return null; }
    @Override public Map<String, String[]> getParameterMap() { return Collections.emptyMap(); }
    @Override public String getContextPath() { return ""; }
    @Override public String getMethod() { return "GET"; }
    @Override public String getRequestURI() { return "/"; }
    @Override public StringBuffer getRequestURL() { return new StringBuffer("http://localhost/"); }
    @Override public String getServletPath() { return ""; }
    @Override public HttpSession getSession(boolean create) { return null; }
    @Override public HttpSession getSession() { return null; }
  }

  private final SyntheticServletRequestBase request = new StubRequest();

  @Test
  @DisplayName("content/IO defaults")
  void contentDefaults() {
    assertEquals("UTF-8", request.getCharacterEncoding());
    request.setCharacterEncoding("ISO-8859-1"); // no-op, must not throw
    assertEquals(0, request.getContentLength());
    assertEquals(0L, request.getContentLengthLong());
    assertEquals("application/json", request.getContentType());
    assertNull(request.getInputStream());
    assertNull(request.getReader());
  }

  @Test
  @DisplayName("protocol/network defaults")
  void networkDefaults() {
    assertEquals("HTTP/1.1", request.getProtocol());
    assertEquals("http", request.getScheme());
    assertEquals("localhost", request.getServerName());
    assertEquals(8080, request.getServerPort());
    assertEquals("127.0.0.1", request.getRemoteAddr());
    assertEquals("localhost", request.getRemoteHost());
    assertEquals(0, request.getRemotePort());
    assertEquals("localhost", request.getLocalName());
    assertEquals("127.0.0.1", request.getLocalAddr());
    assertEquals(8080, request.getLocalPort());
  }

  @Test
  @DisplayName("locale defaults")
  void localeDefaults() {
    assertEquals(Locale.US, request.getLocale());
    Enumeration<Locale> locales = request.getLocales();
    assertTrue(locales.hasMoreElements());
    assertEquals(Locale.US, locales.nextElement());
    assertFalse(locales.hasMoreElements());
  }

  @Test
  @DisplayName("security/dispatcher defaults")
  void securityDispatcherDefaults() {
    assertFalse(request.isSecure());
    assertNull(request.getRequestDispatcher("/x"));
    assertNull(request.getRealPath("/x"));
    assertNull(request.getServletContext());
    assertEquals(DispatcherType.REQUEST, request.getDispatcherType());
  }

  @Test
  @DisplayName("async defaults")
  void asyncDefaults() {
    assertNull(request.startAsync());
    assertNull(request.startAsync(request, null));
    assertFalse(request.isAsyncStarted());
    assertFalse(request.isAsyncSupported());
    assertNull(request.getAsyncContext());
  }

  @Test
  @DisplayName("authentication defaults")
  void authDefaults() {
    assertNull(request.getAuthType());
    assertNull(request.getRemoteUser());
    assertFalse(request.isUserInRole("admin"));
    assertNull(request.getUserPrincipal());
    assertFalse(request.authenticate(null));
    request.login("u", "p"); // no-op
    request.logout(); // no-op
  }

  @Test
  @DisplayName("cookies/headers defaults")
  void headerDefaults() {
    assertNull(request.getCookies());
    assertEquals(-1L, request.getDateHeader("Date"));
    assertNull(request.getHeader("X"));
    assertFalse(request.getHeaders("X").hasMoreElements());
    assertFalse(request.getHeaderNames().hasMoreElements());
    assertEquals(-1, request.getIntHeader("X"));
  }

  @Test
  @DisplayName("url/path defaults")
  void urlPathDefaults() {
    assertNull(request.getPathInfo());
    assertNull(request.getPathTranslated());
    assertNull(request.getQueryString());
  }

  @Test
  @DisplayName("session id validation defaults")
  void sessionIdDefaults() {
    assertNull(request.getRequestedSessionId());
    assertNull(request.changeSessionId());
    assertFalse(request.isRequestedSessionIdValid());
    assertFalse(request.isRequestedSessionIdFromCookie());
    assertFalse(request.isRequestedSessionIdFromURL());
    assertFalse(request.isRequestedSessionIdFromUrl());
  }

  @Test
  @DisplayName("multipart/upgrade defaults")
  void multipartDefaults() throws Exception {
    assertTrue(request.getParts().isEmpty());
    assertNull(request.getPart("file"));
    assertNull(request.upgrade(null));
  }
}
