package com.etendoerp.go.schemaforge;

import java.io.BufferedReader;
import java.io.IOException;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

/**
 * Minimal stub implementation of HttpServletRequest for unit testing.
 * Returns safe defaults for all methods. No actual HTTP request is created.
 */
class StubHttpServletRequest implements HttpServletRequest {

  @Override
  public String getRequestURI() {
    return "/sws/neo/test/entity";
  }

  @Override
  public String getMethod() {
    return "GET";
  }

  @Override
  public String getParameter(String name) {
    return null;
  }

  @Override
  public Enumeration<String> getParameterNames() {
    return Collections.emptyEnumeration();
  }

  @Override
  public String[] getParameterValues(String name) {
    return null;
  }

  @Override
  public Map<String, String[]> getParameterMap() {
    return Collections.emptyMap();
  }

  // -- Remaining HttpServletRequest methods with default stubs --

  @Override public Object getAttribute(String name) { return null; }
  @Override public Enumeration<String> getAttributeNames() { return Collections.emptyEnumeration(); }
  @Override public String getCharacterEncoding() { return "UTF-8"; }
  @Override public void setCharacterEncoding(String env) { }
  @Override public int getContentLength() { return 0; }
  @Override public long getContentLengthLong() { return 0; }
  @Override public String getContentType() { return null; }
  @Override public ServletInputStream getInputStream() { return null; }
  @Override public String getProtocol() { return "HTTP/1.1"; }
  @Override public String getScheme() { return "http"; }
  @Override public String getServerName() { return "localhost"; }
  @Override public int getServerPort() { return 8080; }
  @Override public BufferedReader getReader() { return null; }
  @Override public String getRemoteAddr() { return "127.0.0.1"; }
  @Override public String getRemoteHost() { return "localhost"; }
  @Override public void setAttribute(String name, Object o) { }
  @Override public void removeAttribute(String name) { }
  @Override public Locale getLocale() { return Locale.US; }
  @Override public Enumeration<Locale> getLocales() { return Collections.enumeration(Collections.singletonList(Locale.US)); }
  @Override public boolean isSecure() { return false; }
  @Override public RequestDispatcher getRequestDispatcher(String path) { return null; }
  @Override public String getRealPath(String path) { return null; }
  @Override public int getRemotePort() { return 0; }
  @Override public String getLocalName() { return "localhost"; }
  @Override public String getLocalAddr() { return "127.0.0.1"; }
  @Override public int getLocalPort() { return 8080; }
  @Override public ServletContext getServletContext() { return null; }
  @Override public AsyncContext startAsync() throws IllegalStateException { return null; }
  @Override public AsyncContext startAsync(ServletRequest req, ServletResponse res) { return null; }
  @Override public boolean isAsyncStarted() { return false; }
  @Override public boolean isAsyncSupported() { return false; }
  @Override public AsyncContext getAsyncContext() { return null; }
  @Override public DispatcherType getDispatcherType() { return DispatcherType.REQUEST; }
  @Override public String getAuthType() { return null; }
  @Override public Cookie[] getCookies() { return null; }
  @Override public long getDateHeader(String name) { return -1; }
  @Override public String getHeader(String name) { return null; }
  @Override public Enumeration<String> getHeaders(String name) { return Collections.emptyEnumeration(); }
  @Override public Enumeration<String> getHeaderNames() { return Collections.emptyEnumeration(); }
  @Override public int getIntHeader(String name) { return -1; }
  @Override public String getPathInfo() { return null; }
  @Override public String getPathTranslated() { return null; }
  @Override public String getContextPath() { return "/etendo"; }
  @Override public String getQueryString() { return null; }
  @Override public String getRemoteUser() { return null; }
  @Override public boolean isUserInRole(String role) { return false; }
  @Override public Principal getUserPrincipal() { return null; }
  @Override public String getRequestedSessionId() { return null; }
  @Override public StringBuffer getRequestURL() { return new StringBuffer("http://localhost:8080/etendo/sws/neo/test/entity"); }
  @Override public String getServletPath() { return "/sws/neo"; }
  @Override public HttpSession getSession(boolean create) { return null; }
  @Override public HttpSession getSession() { return null; }
  @Override public String changeSessionId() { return null; }
  @Override public boolean isRequestedSessionIdValid() { return false; }
  @Override public boolean isRequestedSessionIdFromCookie() { return false; }
  @Override public boolean isRequestedSessionIdFromURL() { return false; }
  @Override public boolean isRequestedSessionIdFromUrl() { return false; }
  @Override public boolean authenticate(HttpServletResponse response) { return false; }
  @Override public void login(String username, String password) { }
  @Override public void logout() { }
  @Override public Collection<Part> getParts() { return Collections.emptyList(); }
  @Override public Part getPart(String name) { return null; }
  @Override public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) { return null; }
}
