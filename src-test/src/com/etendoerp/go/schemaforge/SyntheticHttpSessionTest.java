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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SyntheticHttpSession}.
 */
class SyntheticHttpSessionTest {

  @Test
  @DisplayName("constructor upper-cases initial attribute keys")
  void constructorUppercasesKeys() {
    Map<String, Object> init = new HashMap<>();
    init.put("#ad_user_id", "100");
    SyntheticHttpSession session = new SyntheticHttpSession(init);

    assertEquals("100", session.getAttribute("#AD_USER_ID"));
    assertNull(session.getAttribute("#ad_user_id"));
  }

  @Test
  @DisplayName("set/get/remove attribute round-trip")
  void attributeRoundTrip() {
    SyntheticHttpSession session = new SyntheticHttpSession(Collections.emptyMap());
    session.setAttribute("K", "v");
    assertEquals("v", session.getAttribute("K"));

    session.removeAttribute("K");
    assertNull(session.getAttribute("K"));
  }

  @Test
  @DisplayName("getAttributeNames enumerates stored keys")
  void attributeNames() {
    SyntheticHttpSession session = new SyntheticHttpSession(Collections.emptyMap());
    session.setAttribute("A", 1);
    assertTrue(session.getAttributeNames().hasMoreElements());
  }

  @Test
  @DisplayName("legacy value API delegates to attribute API")
  void legacyValueApi() {
    SyntheticHttpSession session = new SyntheticHttpSession(Collections.emptyMap());
    session.putValue("V", "x");
    assertEquals("x", session.getValue("V"));
    assertArrayEquals(new String[] {"V"}, session.getValueNames());

    session.removeValue("V");
    assertNull(session.getValue("V"));
  }

  @Test
  @DisplayName("constant metadata defaults")
  void metadataDefaults() {
    SyntheticHttpSession session = new SyntheticHttpSession(Collections.emptyMap());
    assertEquals("synthetic-callout-session", session.getId());
    assertTrue(session.getCreationTime() > 0);
    assertTrue(session.getLastAccessedTime() > 0);
    assertNull(session.getServletContext());
    assertNull(session.getSessionContext());
    assertEquals(0, session.getMaxInactiveInterval());
    session.setMaxInactiveInterval(30); // no-op, must not throw
    assertTrue(session.isNew());
  }

  @Test
  @DisplayName("invalidate clears all attributes")
  void invalidateClears() {
    Map<String, Object> init = new HashMap<>();
    init.put("K", "v");
    SyntheticHttpSession session = new SyntheticHttpSession(init);

    session.invalidate();

    assertNull(session.getAttribute("K"));
    assertFalse(session.getAttributeNames().hasMoreElements());
  }
}
