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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ETP-5848: what {@code etendo_schema(view:"actions")} hands the agent.
 *
 * @covers com.etendoerp.go.mcp.McpActionsCompact
 */
class McpActionsCompactTest {

  private static JSONObject button(String name, boolean invokable, String reason) throws Exception {
    JSONObject b = new JSONObject().put("name", name).put("action", name).put("column", "Col_" + name)
        .put("label", "Label " + name).put("description", "Label " + name).put("type", "button")
        .put("processId", "P1").put("processType", "Classic").put("visibility", "editable")
        .put("triggerValue", "Y").put("businessCritical", true);
    if (invokable) {
      b.put("invokeVia", "etendo_action").put("actionValues", new JSONArray().put("CO"));
    } else {
      b.put("invokable", false).put("notInvokableReason", reason);
    }
    return b;
  }

  private static JSONObject catalogue() throws Exception {
    JSONArray actions = new JSONArray()
        .put(button("documentAction", true, null))
        .put(button("copyFrom", false, "discarded: not part of the agent surface"))
        .put(button("generateTemplate", false, "discarded: not part of the agent surface"))
        .put(button("aPRMAddPayment", false, "Not run through MCP").put("useInstead", "registerPayment"))
        .put(new JSONObject().put("action", "registerPayment").put("invokeVia", "etendo_action")
            .put("parameters", new JSONObject().put("type", "object")).put("description", "Pays it"));
    return new JSONObject().put("spec", "sales-invoice").put("entity", "header").put("actions", actions)
        .put("actionCount", 5).put("invokableCount", 2);
  }

  private static JSONObject entry(JSONArray actions, String name) throws Exception {
    for (int i = 0; i < actions.length(); i++) {
      if (name.equals(actions.getJSONObject(i).optString("action"))) {
        return actions.getJSONObject(i);
      }
    }
    throw new AssertionError(name + " not in " + actions);
  }

  @Test
  @DisplayName("actions the agent cannot run move to notOffered, grouped by reason, redirect kept")
  void notInvokableGoToNotOffered() throws Exception {
    JSONObject out = McpActionsCompact.shape(catalogue(), null);

    JSONArray actions = out.getJSONArray("actions");
    assertEquals(2, actions.length(), actions.toString());
    assertEquals(2, out.getInt("actionCount"));
    assertEquals(2, out.getInt("invokableCount"));
    JSONArray notOffered = out.getJSONArray("notOffered");
    assertEquals(2, notOffered.length(), "two discarded buttons share one group: " + notOffered);
    assertEquals(2, notOffered.getJSONObject(0).getJSONArray("actions").length());
    JSONObject redirected = notOffered.getJSONObject(1);
    assertEquals("aPRMAddPayment", redirected.getJSONArray("actions").getString(0));
    assertEquals("registerPayment", redirected.getString("useInstead"),
        "a redirect is what the agent needs from a button it cannot run");
  }

  @Test
  @DisplayName("one action in full keeps how to call it and drops the window's own metadata")
  void oneActionIsTrimmed() throws Exception {
    JSONObject doc = McpActionsCompact.shape(catalogue(), "documentAction")
        .getJSONArray("actions").getJSONObject(0);

    for (String gone : new String[] { "column", "type", "processId", "processType", "visibility",
        "triggerValue", "name", "description" }) {
      assertFalse(doc.has(gone), gone + " stays in " + doc);
    }
    assertEquals("etendo_action", doc.getString("invokeVia"));
    assertEquals("Label documentAction", doc.getString("label"));
    assertTrue(doc.has("actionValues"));
    assertTrue(doc.getBoolean("businessCritical"));
  }

  @Test
  @DisplayName("without action the catalogue is an index: name, one-line summary, availability;"
      + " no parameters or values")
  void wholeCatalogueIsAnIndex() throws Exception {
    JSONObject cat = catalogue();
    cat.getJSONArray("actions").getJSONObject(4).put("description",
        "Registers a collection against one installment. With process 'confirm' it is applied.")
        .put("availableWhen", "documentStatus = CO");
    JSONObject out = McpActionsCompact.shape(cat, null);

    JSONObject pay = entry(out.getJSONArray("actions"), "registerPayment");
    assertEquals("Registers a collection against one installment.", pay.getString("summary"));
    assertEquals("documentStatus = CO", pay.getString("availableWhen"));
    assertFalse(pay.has("parameters"));
    assertFalse(pay.has("invokeVia"), "everything listed is invokable");
    JSONObject doc = entry(out.getJSONArray("actions"), "documentAction");
    assertFalse(doc.has("actionValues"));
    assertFalse(doc.has("summary"), "a summary equal to the label adds nothing: " + doc);
    assertTrue(out.getString("hint").contains("action:"));
  }

  @Test
  @DisplayName("a summary is the first sentence, cut to one line")
  void summaryIsTheFirstSentence() {
    assertEquals("One.", McpActionsCompact.summaryOf("One. Two."));
    assertEquals(McpActionsCompact.MAX_SUMMARY,
        McpActionsCompact.summaryOf("x".repeat(500)).length());
    assertEquals(null, McpActionsCompact.summaryOf("  "));
  }

  @Test
  @DisplayName("withheld actions sharing a reason are listed once with every name")
  void withheldAreGroupedByReason() throws Exception {
    JSONObject cat = catalogue().put("withheldActions", new JSONArray()
        .put(new JSONObject().put("action", "a").put("reason", "same paragraph"))
        .put(new JSONObject().put("action", "b").put("reason", "same paragraph"))
        .put(new JSONObject().put("action", "c").put("reason", "other")));

    JSONArray withheld = McpActionsCompact.shape(cat, null).getJSONArray("withheldActions");

    assertEquals(2, withheld.length(), withheld.toString());
    assertEquals(2, withheld.getJSONObject(0).getJSONArray("actions").length());
    assertEquals("same paragraph", withheld.getJSONObject(0).getString("reason"));
  }

  @Test
  @DisplayName("action:<name> answers that action alone, matching its name in any case")
  void oneAction() throws Exception {
    JSONObject out = McpActionsCompact.shape(catalogue(), "REGISTERPAYMENT");

    JSONArray actions = out.getJSONArray("actions");
    assertEquals(1, actions.length());
    assertEquals("registerPayment", actions.getJSONObject(0).getString("action"));
    assertEquals("Pays it", actions.getJSONObject(0).getString("description"));
    assertFalse(out.has("notOffered"));
    assertEquals(1, out.getInt("actionCount"));
  }

  @Test
  @DisplayName("action:<a not-offered name> answers why, and nothing to run")
  void oneNotOfferedAction() throws Exception {
    JSONObject out = McpActionsCompact.shape(catalogue(), "aPRMAddPayment");

    assertEquals(0, out.getJSONArray("actions").length());
    JSONObject group = out.getJSONArray("notOffered").getJSONObject(0);
    assertEquals(1, group.getJSONArray("actions").length());
    assertEquals("registerPayment", group.getString("useInstead"));
  }

  @Test
  @DisplayName("an unknown action is refused with every name that exists, not ignored")
  void unknownActionIsRefused() throws Exception {
    McpRoutingException e = assertThrows(McpRoutingException.class,
        () -> McpActionsCompact.shape(catalogue(), "voidInvoice"));
    JSONObject envelope = e.toEnvelope();
    assertEquals(422, envelope.getInt("status"));
    String available = envelope.getJSONArray("available").toString();
    assertTrue(available.contains("registerPayment") && available.contains("copyFrom"), available);
  }
}
