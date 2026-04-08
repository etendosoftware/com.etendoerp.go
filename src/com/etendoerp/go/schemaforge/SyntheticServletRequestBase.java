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
import java.util.Locale;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

/**
 * Abstract base class that provides safe no-op defaults for all
 * {@link javax.servlet.ServletRequest} and {@link javax.servlet.http.HttpServletRequest}
 * methods that are not relevant to the synthetic callout execution context.
 * <p>
 * Subclasses only need to override the methods that carry real behaviour.
 * </p>
 */
public abstract class SyntheticServletRequestBase implements javax.servlet.http.HttpServletRequest {

  /** Local hostname constant used for synthetic network metadata. */
  protected static final String LOCALHOST = "localhost";

  // -- Content / IO --

  @Override public String getCharacterEncoding() { return "UTF-8"; }
  @Override public void setCharacterEncoding(String env) { }
  @Override public int getContentLength() { return 0; }
  @Override public long getContentLengthLong() { return 0; }
  @Override public String getContentType() { return "application/json"; }
  @Override public ServletInputStream getInputStream() { return null; }
  @Override public BufferedReader getReader() { return null; }

  // -- Protocol / network --

  @Override public String getProtocol() { return "HTTP/1.1"; }
  @Override public String getScheme() { return "http"; }
  @Override public String getServerName() { return LOCALHOST; }
  @Override public int getServerPort() { return 8080; }
  @Override public String getRemoteAddr() { return "127.0.0.1"; }
  @Override public String getRemoteHost() { return LOCALHOST; }
  @Override public int getRemotePort() { return 0; }
  @Override public String getLocalName() { return LOCALHOST; }
  @Override public String getLocalAddr() { return "127.0.0.1"; }
  @Override public int getLocalPort() { return 8080; }

  // -- Locale --

  @Override public Locale getLocale() { return Locale.US; }
  @Override public Enumeration<Locale> getLocales() {
    return Collections.enumeration(Collections.singletonList(Locale.US));
  }

  // -- Security / dispatcher --

  @Override public boolean isSecure() { return false; }
  @Override public RequestDispatcher getRequestDispatcher(String path) { return null; }
  @Override public String getRealPath(String path) { return null; }
  @Override public ServletContext getServletContext() { return null; }

  // -- Async --

  @Override public AsyncContext startAsync() throws IllegalStateException { return null; }
  @Override public AsyncContext startAsync(ServletRequest req, ServletResponse res) { return null; }
  @Override public boolean isAsyncStarted() { return false; }
  @Override public boolean isAsyncSupported() { return false; }
  @Override public AsyncContext getAsyncContext() { return null; }
  @Override public DispatcherType getDispatcherType() { return DispatcherType.REQUEST; }

  // -- Authentication --

  @Override public String getAuthType() { return null; }
  @Override public String getRemoteUser() { return null; }
  @Override public boolean isUserInRole(String role) { return false; }
  @Override public Principal getUserPrincipal() { return null; }
  @Override public boolean authenticate(HttpServletResponse response) { return false; }
  @Override public void login(String username, String password) { }
  @Override public void logout() { }

  // -- Cookies --

  @Override public Cookie[] getCookies() { return null; }

  // -- Headers --

  @Override public long getDateHeader(String name) { return -1; }
  @Override public String getHeader(String name) { return null; }
  @Override public Enumeration<String> getHeaders(String name) {
    return Collections.emptyEnumeration();
  }
  @Override public Enumeration<String> getHeaderNames() {
    return Collections.emptyEnumeration();
  }
  @Override public int getIntHeader(String name) { return -1; }

  // -- URL / path --

  @Override public String getPathInfo() { return null; }
  @Override public String getPathTranslated() { return null; }
  @Override public String getQueryString() { return null; }

  // -- Session ID validation --

  @Override public String getRequestedSessionId() { return null; }
  @Override public String changeSessionId() { return null; }
  @Override public boolean isRequestedSessionIdValid() { return false; }
  @Override public boolean isRequestedSessionIdFromCookie() { return false; }
  @Override public boolean isRequestedSessionIdFromURL() { return false; }
  @Override public boolean isRequestedSessionIdFromUrl() { return false; }

  // -- Multipart / upgrade --

  @Override public Collection<Part> getParts() { return Collections.emptyList(); }
  @Override public Part getPart(String name) { return null; }
  @Override public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) { return null; }
}
