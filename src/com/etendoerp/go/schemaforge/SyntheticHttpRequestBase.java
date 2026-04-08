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

import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

/**
 * Abstract base class that extends {@link SyntheticServletRequestBase} and adds
 * safe no-op defaults for all HTTP-specific {@link javax.servlet.http.HttpServletRequest}
 * methods that are irrelevant to synthetic callout execution.
 * <p>
 * Covered areas: authentication, cookies, HTTP headers, URL paths, session-ID
 * validation, multipart and upgrade.
 * </p>
 */
public abstract class SyntheticHttpRequestBase extends SyntheticServletRequestBase {

  // -- Authentication / security --

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
