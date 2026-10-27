/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link NeoHints}: where a hint lands in a write response per channel, and which
 * catalog its text comes from. This pins the bundle choice per language (with the ETP-5840 exchange
 * rate message as the sample key) and the envelope itself.
 *
 * @covers com.etendoerp.go.schemaforge.NeoHints
 */
public class NeoHintsTest {

  private static final String KEY = "ETGO_ExchangeRateMissingOnSave";
  private static final String EN_PREFIX = "There is no exchange rate from EUR to USD";
  private static final String ES_PREFIX = "No hay tipo de cambio de EUR a USD";

  private static JSONObject coreResponse() throws Exception {
    return new JSONObject().put("response", new JSONObject().put("status", 0));
  }

  /** MCP reads {@code messages} inside core's {@code response} wrapper; REST reads it at the root. */
  @Test
  public void addMessageGoesInsideTheWrapperOnMcpAndAtTheRootOnRest() throws Exception {
    JSONObject mcp = coreResponse();
    JSONObject rest = coreResponse();

    NeoHints.addMessage(mcp, true, NeoHints.TYPE_WARNING, "hint");
    NeoHints.addMessage(rest, false, NeoHints.TYPE_WARNING, "hint");

    JSONObject mcpMessage = mcp.getJSONObject("response").getJSONArray("messages").getJSONObject(0);
    assertEquals("warning", mcpMessage.getString("type"));
    assertEquals("hint", mcpMessage.getString("text"));
    assertFalse(mcp.has("messages"));

    assertEquals("hint", rest.getJSONArray("messages").getJSONObject(0).getString("text"));
    assertFalse(rest.getJSONObject("response").has("messages"));
  }

  /** An MCP body without a wrapper takes the hint at its root, appended after existing messages. */
  @Test
  public void addMessageWithoutWrapperAppendsAtTheRoot() throws Exception {
    JSONArray existing = new JSONArray().put(new JSONObject().put("type", "info").put("text", "a"));
    JSONObject flat = new JSONObject().put("id", "inv-1").put("messages", existing);

    NeoHints.addMessage(flat, true, NeoHints.TYPE_WARNING, "b");

    JSONArray messages = flat.getJSONArray("messages");
    assertEquals(2, messages.length());
    assertEquals("a", messages.getJSONObject(0).getString("text"));
    assertEquals("b", messages.getJSONObject(1).getString("text"));
  }

  /**
   * Any Spanish variant reads the es catalog; other, unknown or blank languages read English; a
   * key the catalog lacks comes back as itself.
   */
  @Test
  public void textPicksTheBundleByLanguage() {
    Object[] args = { "EUR", "USD", "14/10/2026", "Fecha contable" };

    assertTrue(NeoHints.text(KEY, "es_AR", args).startsWith(ES_PREFIX));
    assertTrue(NeoHints.text(KEY, "en_US", args).startsWith(EN_PREFIX));
    assertTrue(NeoHints.text(KEY, "fr_FR", args).startsWith(EN_PREFIX));
    assertTrue(NeoHints.text(KEY, "", args).startsWith(EN_PREFIX));
    assertEquals("noSuchHint", NeoHints.text("noSuchHint", "es_ES"));
  }
}
