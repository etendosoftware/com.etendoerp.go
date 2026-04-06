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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.io.BufferedReader;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

/**
 * Minimal HttpServletRequest implementation for callout execution.
 * Provides request parameters from a Map and session attributes
 * for OBContext values (user, role, org, client, warehouse).
 *
 * Used by NeoCalloutService to build a synthetic request that
 * SimpleCallout and VariablesSecureApp can consume without a
 * real HTTP request.
 */
public class SyntheticHttpServletRequest implements HttpServletRequest {

  /** Local hostname used for synthetic request metadata. */
  private static final String LOCALHOST = "localhost";

  private final Map<String, String[]> parameters;
  private final Map<String, Object> attributes;
  private final SyntheticHttpSession syntheticSession;

  /**
   * Create a synthetic request with the given parameters and session attributes.
   *
   * @param parameters     request parameters (inp* names to values)
   * @param sessionAttrs   session attributes (#AD_User_ID, #AD_Role_ID, etc.)
   */
  public SyntheticHttpServletRequest(Map<String, String[]> parameters,
      Map<String, Object> sessionAttrs) {
    this.parameters = parameters != null ? parameters : new HashMap<>();
    this.attributes = new HashMap<>();
    this.syntheticSession = new SyntheticHttpSession(
        sessionAttrs != null ? sessionAttrs : new HashMap<>());
  }

  // -- Parameter methods (used by VariablesBase.getStringParameter) --

  @Override
  public String getParameter(String name) {
    String[] values = parameters.get(name);
    return (values != null && values.length > 0) ? values[0] : null;
  }

  @Override
  public Enumeration<String> getParameterNames() {
    return Collections.enumeration(parameters.keySet());
  }

  @Override
  public String[] getParameterValues(String name) {
    return parameters.get(name);
  }

  @Override
  public Map<String, String[]> getParameterMap() {
    return Collections.unmodifiableMap(parameters);
  }

  // -- Session methods (used by VariablesBase.getSessionValue) --

  @Override
  public HttpSession getSession(boolean create) {
    return syntheticSession;
  }

  @Override
  public HttpSession getSession() {
    return syntheticSession;
  }

  // -- Attribute methods --

  @Override
  public Object getAttribute(String name) {
    return attributes.get(name);
  }

  @Override
  public void setAttribute(String name, Object o) {
    attributes.put(name, o);
  }

  @Override
  public void removeAttribute(String name) {
    attributes.remove(name);
  }

  @Override
  public Enumeration<String> getAttributeNames() {
    return Collections.enumeration(attributes.keySet());
  }

  // -- Remaining HttpServletRequest methods with safe defaults --

  @Override public String getRequestURI() { return "/sws/neo/callout"; }
  @Override public String getMethod() { return "POST"; }
  @Override public String getCharacterEncoding() { return "UTF-8"; }
  @Override public void setCharacterEncoding(String env) { }
  @Override public int getContentLength() { return 0; }
  @Override public long getContentLengthLong() { return 0; }
  @Override public String getContentType() { return "application/json"; }
  @Override public ServletInputStream getInputStream() { return null; }
  @Override public String getProtocol() { return "HTTP/1.1"; }
  @Override public String getScheme() { return "http"; }
  @Override public String getServerName() { return LOCALHOST; }
  @Override public int getServerPort() { return 8080; }
  @Override public BufferedReader getReader() { return null; }
  @Override public String getRemoteAddr() { return "127.0.0.1"; }
  @Override public String getRemoteHost() { return LOCALHOST; }
  @Override public Locale getLocale() { return Locale.US; }
  @Override public Enumeration<Locale> getLocales() {
    return Collections.enumeration(Collections.singletonList(Locale.US));
  }
  @Override public boolean isSecure() { return false; }
  @Override public RequestDispatcher getRequestDispatcher(String path) { return null; }
  @Override public String getRealPath(String path) { return null; }
  @Override public int getRemotePort() { return 0; }
  @Override public String getLocalName() { return LOCALHOST; }
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
  @Override public Enumeration<String> getHeaders(String name) {
    return Collections.emptyEnumeration();
  }
  @Override public Enumeration<String> getHeaderNames() {
    return Collections.emptyEnumeration();
  }
  @Override public int getIntHeader(String name) { return -1; }
  @Override public String getPathInfo() { return null; }
  @Override public String getPathTranslated() { return null; }
  @Override public String getContextPath() { return "/etendo"; }
  @Override public String getQueryString() { return null; }
  @Override public String getRemoteUser() { return null; }
  @Override public boolean isUserInRole(String role) { return false; }
  @Override public Principal getUserPrincipal() { return null; }
  @Override public String getRequestedSessionId() { return null; }
  @Override public StringBuffer getRequestURL() {
    return new StringBuffer("http://localhost:8080/etendo/sws/neo/callout");
  }
  @Override public String getServletPath() { return "/sws/neo"; }
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

  /**
   * Minimal HttpSession implementation backed by a Map.
   * Supports getAttribute/setAttribute which is what VariablesBase.getSessionValue uses.
   */
  static class SyntheticHttpSession implements HttpSession {

    private final Map<String, Object> attributes;

    SyntheticHttpSession(Map<String, Object> initialAttributes) {
      this.attributes = new HashMap<>();
      // Store all attributes with uppercase keys (VariablesBase looks up with toUpperCase)
      for (Map.Entry<String, Object> entry : initialAttributes.entrySet()) {
        this.attributes.put(entry.getKey().toUpperCase(), entry.getValue());
      }
    }

    @Override
    public Object getAttribute(String name) {
      return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
      attributes.put(name, value);
    }

    @Override
    public void removeAttribute(String name) {
      attributes.remove(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
      return Collections.enumeration(attributes.keySet());
    }

    // -- Remaining HttpSession methods with defaults --

    @Override public long getCreationTime() { return System.currentTimeMillis(); }
    @Override public String getId() { return "synthetic-callout-session"; }
    @Override public long getLastAccessedTime() { return System.currentTimeMillis(); }
    @Override public javax.servlet.ServletContext getServletContext() { return null; }
    @Override public void setMaxInactiveInterval(int interval) { }
    @Override public int getMaxInactiveInterval() { return 0; }
    @SuppressWarnings("deprecation")
    @Override public HttpSessionContext getSessionContext() { return null; }
    @Override public Object getValue(String name) { return getAttribute(name); }
    @Override public String[] getValueNames() {
      return attributes.keySet().toArray(new String[0]);
    }
    @Override public void putValue(String name, Object value) { setAttribute(name, value); }
    @Override public void removeValue(String name) { removeAttribute(name); }
    @Override public void invalidate() { attributes.clear(); }
    @Override public boolean isNew() { return true; }
  }
}
