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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SyntheticHttpServletRequest}'s {@code inp*} alias resolution
 * (ETP-4784).
 *
 * <p>Regression coverage for the bug where {@code CalloutRequestBuilder} puts some context
 * params under their bare camelCase name (e.g. {@code "isSOTrx"}) while legacy AD callouts
 * read raw HTTP params via {@code inp<field>} (e.g. {@code "inpissotrx"}). Because the names
 * didn't match, {@code getParameter("inpissotrx")} silently returned {@code null} and any
 * callout branch gated on it never ran (SII default in {@code SiiAutoSetSIIKEYByDefault}).</p>
 */
class SyntheticHttpServletRequestTest {

  private static SyntheticHttpServletRequest requestWith(Map<String, String[]> params) {
    return new SyntheticHttpServletRequest(params, new HashMap<>());
  }

  // ── Exact match — must keep working exactly as before ──────────────

  @Test
  @DisplayName("getParameter returns the exact match when the key is present verbatim")
  void getParameterExactMatchTakesPriority() {
    Map<String, String[]> params = new HashMap<>();
    params.put("inpcBpartnerId", new String[]{ "BP-001" });

    SyntheticHttpServletRequest request = requestWith(params);

    assertEquals("BP-001", request.getParameter("inpcBpartnerId"));
  }

  @Test
  @DisplayName("getParameter returns null when neither the exact key nor an alias exists")
  void getParameterMissingKeyReturnsNull() {
    SyntheticHttpServletRequest request = requestWith(new HashMap<>());

    assertNull(request.getParameter("inpDoesNotExist"));
  }

  // ── The actual ETP-4784 regression: isSOTrx -> inpissotrx ──────────

  @Test
  @DisplayName("getParameter(\"inpissotrx\") resolves a bare \"isSOTrx\" param (ETP-4784)")
  void getParameterResolvesInpIssotrxFromBareIsSOTrx() {
    Map<String, String[]> params = new HashMap<>();
    params.put("isSOTrx", new String[]{ "Y" });

    SyntheticHttpServletRequest request = requestWith(params);

    assertEquals("Y", request.getParameter("inpissotrx"));
    // The original key must keep resolving too — purely additive fallback.
    assertEquals("Y", request.getParameter("isSOTrx"));
  }

  @Test
  @DisplayName("getParameterValues(\"inpissotrx\") resolves a bare \"isSOTrx\" param")
  void getParameterValuesResolvesInpIssotrxFromBareIsSOTrx() {
    Map<String, String[]> params = new HashMap<>();
    params.put("isSOTrx", new String[]{ "N" });

    SyntheticHttpServletRequest request = requestWith(params);

    assertArrayEquals(new String[]{ "N" }, request.getParameterValues("inpissotrx"));
  }

  // ── Generic case: any custom field, not just isSOTrx ────────────────

  @Test
  @DisplayName("getParameter resolves inp<field> for an arbitrary bare camelCase param")
  void getParameterResolvesGenericInpAliasForCustomField() {
    Map<String, String[]> params = new HashMap<>();
    params.put("myCustomFlag", new String[]{ "42" });

    SyntheticHttpServletRequest request = requestWith(params);

    assertEquals("42", request.getParameter("inpmyCustomFlag"));
    assertEquals("42", request.getParameter("inpmycustomflag"));
  }

  @Test
  @DisplayName("getParameter resolves the reverse direction: bare name from a stored inp* key")
  void getParameterResolvesBareNameFromStoredInpKey() {
    Map<String, String[]> params = new HashMap<>();
    params.put("inpcBpartnerId", new String[]{ "BP-002" });

    SyntheticHttpServletRequest request = requestWith(params);

    // Not the normal access pattern for legacy callouts, but the alias index is
    // symmetric — verifies normalization strips "inp" consistently either way.
    assertEquals("BP-002", request.getParameter("cBpartnerId"));
  }

  @Test
  @DisplayName("inp-prefixed stored key wins over a colliding bare key when both exist")
  void inpPrefixedKeyTakesPriorityOnCollision() {
    Map<String, String[]> params = new HashMap<>();
    // Canonical inp* form (what CalloutRequestBuilder normally produces).
    params.put("inpissotrx", new String[]{ "Y" });
    // A hypothetical stray bare-name duplicate with a different value.
    params.put("isSOTrx", new String[]{ "N" });

    SyntheticHttpServletRequest request = requestWith(params);

    // Exact match wins for both — no ambiguity when the queried name matches a
    // real stored key directly.
    assertEquals("Y", request.getParameter("inpissotrx"));
    assertEquals("N", request.getParameter("isSOTrx"));
  }

  @Test
  @DisplayName("case-insensitive alias match: inpISSOTRX resolves a lowercase-stored key")
  void getParameterAliasMatchIsCaseInsensitive() {
    Map<String, String[]> params = new HashMap<>();
    params.put("issotrx", new String[]{ "Y" });

    SyntheticHttpServletRequest request = requestWith(params);

    assertEquals("Y", request.getParameter("inpISSOTRX"));
  }
}
