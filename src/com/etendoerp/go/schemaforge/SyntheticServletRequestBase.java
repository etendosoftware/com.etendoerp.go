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

/**
 * Abstract base class that provides safe no-op defaults for all
 * {@link javax.servlet.ServletRequest} methods that are not relevant
 * to the synthetic callout execution context.
 * <p>
 * Subclasses only need to override the methods that carry real behaviour.
 * </p>
 */
public abstract class SyntheticServletRequestBase implements javax.servlet.http.HttpServletRequest {

  /** Local hostname constant used for synthetic network metadata. */
  protected static final String LOCALHOST = "localhost";

  // -- Content --

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
}
