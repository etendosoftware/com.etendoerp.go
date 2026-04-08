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

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;

/**
 * Minimal {@link HttpSession} implementation backed by a {@link Map}.
 * <p>
 * Supports {@code getAttribute}/{@code setAttribute} which is what
 * {@code VariablesBase.getSessionValue} uses. All session attributes are
 * stored with upper-case keys because {@code VariablesBase} performs a
 * {@code toUpperCase()} lookup.
 * </p>
 * <p>
 * Used by {@link SyntheticHttpServletRequest} to provide a session without a
 * real HTTP container.
 * </p>
 */
public class SyntheticHttpSession implements HttpSession {

  private final Map<String, Object> attributes;

  /**
   * Create a session pre-populated with the given attributes.
   * Keys are stored in upper-case to match {@code VariablesBase} lookup behaviour.
   *
   * @param initialAttributes initial session attributes (e.g. {@code #AD_User_ID})
   */
  public SyntheticHttpSession(Map<String, Object> initialAttributes) {
    this.attributes = new HashMap<>();
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

  // -- Remaining HttpSession methods with safe defaults --

  @Override public long getCreationTime() { return System.currentTimeMillis(); }
  @Override public String getId() { return "synthetic-callout-session"; }
  @Override public long getLastAccessedTime() { return System.currentTimeMillis(); }
  @Override public ServletContext getServletContext() { return null; }
  @Override public void setMaxInactiveInterval(int interval) { /* no-op: synthetic session does not expire */ }
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
