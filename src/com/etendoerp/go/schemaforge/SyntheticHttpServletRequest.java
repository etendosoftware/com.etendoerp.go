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
  private final Map<String, String> inpAliasIndex;
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
    this.inpAliasIndex = buildInpAliasIndex(this.parameters);
    this.attributes = new HashMap<>();
    this.syntheticSession = new SyntheticHttpSession(
        sessionAttrs != null ? sessionAttrs : new HashMap<>());
  }

  // -- inp* alias resolution ------------------------------------------
  //
  // Classic Etendo callouts (SimpleCallout subclasses, e.g. SE_Invoice_BPartner /
  // SiiAutoSetSIIKEYByDefault) read raw HTTP parameters via
  // VariablesBase#getStringParameter using the lower('inp' + fieldName) convention
  // (e.g. "inpissotrx"). CalloutRequestBuilder maps most form-state/context params
  // through NeoCalloutService#toInpName so they already land in that exact shape,
  // but a few context params historically bypassed that mapping and were put
  // directly under their camelCase name (e.g. "isSOTrx" instead of "inpissotrx").
  // A callout asking for the inp* name of such a param got a silent null/blank
  // value and any branch gated on it (like SII default) never executed — ETP-4784.
  //
  // Rather than hunting down and renaming every such param one at a time (fragile,
  // and this class is shared by every window's callouts), getParameter/
  // getParameterValues fall back to a normalized alias index: a parameter is
  // matched case-insensitively, and with an optional leading "inp" stripped from
  // either side. The exact key is always tried FIRST, so any parameter that
  // already resolves correctly today keeps behaving exactly as before — this is
  // purely an additive fallback for names that would otherwise resolve to null.

  /**
   * Build a lookup from normalized parameter name to the actual stored key, so that
   * {@code getParameter("inpissotrx")} can resolve a stored {@code "isSOTrx"} entry
   * (and vice versa) regardless of casing or the presence of the {@code inp} prefix.
   *
   * <p>Keys that are already {@code inp}-prefixed are indexed first and win ties,
   * since that is the canonical form legacy callouts expect; a second pass fills in
   * any normalized name not yet covered from the remaining (non-{@code inp}) keys.</p>
   */
  private static Map<String, String> buildInpAliasIndex(Map<String, String[]> params) {
    Map<String, String> index = new HashMap<>();
    for (String key : params.keySet()) {
      if (startsWithInpIgnoreCase(key)) {
        index.put(normalizeParamName(key), key);
      }
    }
    for (String key : params.keySet()) {
      index.putIfAbsent(normalizeParamName(key), key);
    }
    return index;
  }

  private static boolean startsWithInpIgnoreCase(String key) {
    return key != null && key.length() > 3 && key.regionMatches(true, 0, "inp", 0, 3);
  }

  /**
   * Normalize a parameter name for alias matching: lowercase, with a leading
   * {@code inp} prefix stripped when present. {@code "inpissotrx"} and
   * {@code "isSOTrx"} both normalize to {@code "issotrx"}.
   */
  private static String normalizeParamName(String name) {
    if (name == null) {
      return "";
    }
    String lower = name.toLowerCase();
    return startsWithInpIgnoreCase(name) ? lower.substring(3) : lower;
  }

  /**
   * Resolve {@code name} to its stored parameter values, first by exact key and,
   * failing that, via the {@code inp}-alias index (see {@link #buildInpAliasIndex}).
   */
  private String[] resolveParameterValues(String name) {
    String[] values = parameters.get(name);
    if (values != null) {
      return values;
    }
    String aliasKey = inpAliasIndex.get(normalizeParamName(name));
    return aliasKey != null ? parameters.get(aliasKey) : null;
  }

  // -- Parameter methods (used by VariablesBase.getStringParameter) --

  @Override
  public String getParameter(String name) {
    String[] values = resolveParameterValues(name);
    return (values != null && values.length > 0) ? values[0] : null;
  }

  @Override
  public Enumeration<String> getParameterNames() {
    return Collections.enumeration(parameters.keySet());
  }

  @Override
  public String[] getParameterValues(String name) {
    return resolveParameterValues(name);
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
