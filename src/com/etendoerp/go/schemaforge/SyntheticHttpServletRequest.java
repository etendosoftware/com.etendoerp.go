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

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpSession;

/**
 * Minimal {@link javax.servlet.http.HttpServletRequest} implementation for
 * callout execution.
 * <p>
 * Provides request parameters from a {@link Map} and session attributes for
 * OBContext values (user, role, org, client, warehouse). All HTTP/Servlet
 * no-op stubs are inherited from {@link SyntheticServletRequestBase}.
 * </p>
 * Used by {@code NeoCalloutService} to build a synthetic request that
 * {@code SimpleCallout} and {@code VariablesSecureApp} can consume without a
 * real HTTP request.
 */
public class SyntheticHttpServletRequest extends SyntheticServletRequestBase {

  private final Map<String, String[]> parameters;
  private final Map<String, Object> attributes;
  private final SyntheticHttpSession syntheticSession;

  /**
   * Create a synthetic request with the given parameters and session attributes.
   *
   * @param parameters   request parameters ({@code inp*} names to values)
   * @param sessionAttrs session attributes ({@code #AD_User_ID}, {@code #AD_Role_ID}, etc.)
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

  // -- HTTP request identity --

  @Override public String getMethod() { return "POST"; }
  @Override public String getRequestURI() { return "/sws/neo/callout"; }
  @Override public String getContextPath() { return "/etendo"; }
  @Override public String getServletPath() { return "/sws/neo"; }
  @Override public StringBuffer getRequestURL() {
    return new StringBuffer("http://localhost:8080/etendo/sws/neo/callout");
  }
}
