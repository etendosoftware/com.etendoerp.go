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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IMP-45, the MCP half — {@code supersededDefaults} on the {@code neo_create} response.
 *
 * <p>The divergence is recorded in {@code NeoDefaultsCascadeHelper} (covered by
 * {@code NeoSupersededDefaultsTest}) and travels on {@code NeoContext}. This is what the agent
 * actually sees, and the two things that make it worth sending: the hint has to explain that the
 * value it sent was <b>kept</b> — otherwise an agent reads a warning and retries a write that
 * already did what it asked — and it has to name the remedy, which is omitting the field rather
 * than sending a different one.</p>
 */
@DisplayName("IMP-45 — supersededDefaults on the create response")
class McpSupersededDefaultsReportTest {

  private static JSONObject divergence() throws Exception {
    return new JSONObject().put("paymentTerms",
        new JSONObject().put("sent", "30-dias-id").put("callout", "inmediato-id"));
  }

  @Test
  @DisplayName("the divergences and their hint are attached to the body")
  void theReportIsAttached() throws Exception {
    JSONObject body = new JSONObject().put("id", "ord-1");

    McpWriteRequestSupport.reportSupersededDefaults(body, divergence());

    JSONObject reported = body.getJSONObject("supersededDefaults");
    assertEquals("30-dias-id", reported.getJSONObject("paymentTerms").getString("sent"));
    assertEquals("inmediato-id", reported.getJSONObject("paymentTerms").getString("callout"));
    assertEquals("ord-1", body.getString("id"), "the record itself must be untouched");
  }

  /**
   * An agent that reads this as "your write was rejected" will retry, and the retry sends the same
   * value again. The hint has to say the value was kept, and what to do instead.
   */
  @Test
  @DisplayName("the hint says the caller's value was kept and names the remedy")
  void theHintIsActionable() throws Exception {
    JSONObject body = new JSONObject();
    McpWriteRequestSupport.reportSupersededDefaults(body, divergence());

    String hint = body.getString("supersededDefaultsHint").toLowerCase(Locale.ROOT);
    assertTrue(hint.contains("kept"), "the write succeeded — an agent must not read this as a "
        + "rejection and retry");
    assertTrue(hint.contains("neo_defaults"),
        "the hint must name where a blindly echoed value came from");
    assertTrue(hint.contains("omit"),
        "and the remedy is to omit the field, not to guess a different value");
  }

  /**
   * The clean case is the common one. A key present on every create — even empty — is a key the
   * agent learns to skip, and then it skips the populated one too.
   */
  @Test
  @DisplayName("a create where nothing diverged carries no key at all")
  void nothingIsReportedWhenNothingDiverged() throws Exception {
    JSONObject body = new JSONObject().put("id", "ord-1");

    McpWriteRequestSupport.reportSupersededDefaults(body, null);
    McpWriteRequestSupport.reportSupersededDefaults(body, new JSONObject());

    assertFalse(body.has("supersededDefaults"));
    assertFalse(body.has("supersededDefaultsHint"));
    assertEquals(1, body.length());
  }

  @Test
  @DisplayName("a null body is a no-op, not a failure")
  void aNullBodyIsSurvivable() throws Exception {
    McpWriteRequestSupport.reportSupersededDefaults(null, divergence());
  }

  /**
   * <b>Create only.</b> An update carries no {@code neo_defaults} invitation and there is no
   * cascade of this shape behind it, so the same report on {@code handleUpdate} would be a warning
   * about a sequence the caller never ran. The call site is the only place that can say so —
   * nothing in a signature distinguishes the two verbs.
   */
  @Test
  @DisplayName("handleCreate reports it and handleUpdate does not")
  void onlyCreateReportsIt() {
    String source = McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java");

    String create = McpSourceScanner.methodBody(source, "handleCreate");
    assertTrue(create.contains("reportSupersededDefaults"),
        "handleCreate no longer reports the divergence, so an agent that echoed a generic default"
            + " over the one its business partner implies is told nothing again");
    assertTrue(create.contains("getSupersededDefaults"),
        "and it must read them off the context the cascade ran under");

    String update = McpSourceScanner.methodBody(source, "handleUpdate");
    assertFalse(update.contains("reportSupersededDefaults"),
        "handleUpdate has no defaults invitation behind it, so this report would be a warning"
            + " about a sequence the caller never ran");
  }
}
