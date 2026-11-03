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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;

/**
 * IMP-45, the MCP half — {@code supersededDefaults} on the {@code etendo_create} response.
 *
 * <p>The divergence is recorded in {@code NeoDefaultsCascadeHelper} (covered by
 * {@code NeoSupersededDefaultsTest}) and travels on {@code NeoContext}. This is what the agent
 * actually sees, and the two things that make it worth sending: the hint has to explain that the
 * value it sent was <b>kept</b> — otherwise an agent reads a warning and retries a write that
 * already did what it asked — and it has to name the remedy, which is omitting the field rather
 * than sending a different one.</p>
 *
 * <p>ETP-5680 added the reconcile step: the cascade records the divergence before the entity's
 * pre-hook runs, so the report is checked against the final body (and the customization's
 * declared {@code governedCreateFields}) before it is handed to the agent.</p>
 *
 * @covers com.etendoerp.go.mcp.McpWriteRequestSupport
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
    assertTrue(hint.contains("saved as sent"), "the write succeeded — an agent must not read this "
        + "as a rejection and retry");
    assertFalse(hint.contains("was kept"), "'kept' was the claim a pre-hook rewrite made false; "
        + "the hint may only say what is true of every entry that survives the reconcile");
    assertTrue(hint.contains("etendo_defaults"),
        "the hint must name where a blindly echoed value came from");
    assertTrue(hint.contains("ignore") && hint.contains("0 or empty"),
        "a callout that merely proposed 0/empty, or a field another rule governs, is ignorable");
    assertFalse(hint.contains("omit that field"),
        "omitting is wrong when the callout only proposed 0 for a field another rule governs");
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

  private static JSONObject entry(Object sent, Object callout) throws Exception {
    return new JSONObject().put("sent", sent).put("callout", callout);
  }

  private static NeoHandler governing(String... fields) {
    NeoHandler handler = mock(NeoHandler.class);
    when(handler.governedCreateFields(org.mockito.ArgumentMatchers.any())).thenReturn(Set.of(fields));
    return handler;
  }

  private static JSONObject recon(JSONObject superseded, JSONObject pre, JSONObject fin,
      NeoHandler handler) {
    JSONObject body = new JSONObject();
    McpWriteRequestSupport.reconcileSupersededDefaults(body, superseded, pre, fin, handler,
        mock(NeoContext.class));
    return body;
  }

  /**
   * ETP-5680 (CP-M2): a service product sent with stocked:true and returnable:true. A callout
   * proposed "N" for stocked only; the pre-hook forced both off.
   */
  @Test
  @DisplayName("reconcile: a field the pre-hook rewrote is adjusted, even if no callout proposed it")
  void aRewrittenValueIsAdjustedWhateverTheCalloutsDid() throws Exception {
    JSONObject superseded = new JSONObject().put("stocked", entry(true, "N"))
        .put("paymentTerms", entry("30-id", "inmediato-id"));
    JSONObject pre = new JSONObject().put("stocked", true).put("returnable", true)
        .put("paymentTerms", "30-id");
    JSONObject fin = new JSONObject().put("stocked", false).put("returnable", false)
        .put("paymentTerms", "30-id");

    JSONObject body = recon(superseded, pre, fin, null);

    JSONObject adjusted = body.getJSONObject("adjustedFields");
    assertEquals(2, adjusted.length(), "returnable was never recorded as superseded, still reported");
    assertEquals(true, adjusted.getJSONObject("returnable").getBoolean("sent"));
    assertEquals(false, adjusted.getJSONObject("returnable").getBoolean("persisted"));
    assertEquals(false, adjusted.getJSONObject("stocked").getBoolean("persisted"));
    assertFalse(superseded.has("stocked"), "not listed twice");
    assertTrue(superseded.has("paymentTerms"), "an untouched entry stays, shape unchanged");
    assertTrue(body.getString("adjustedFieldsHint").contains("persisted"));
  }

  @Test
  @DisplayName("reconcile: a field the caller did not send but the pre-hook set is not reported")
  void aFieldTheCallerDidNotSendIsNotReported() throws Exception {
    JSONObject body = recon(null, new JSONObject().put("name", "x"),
        new JSONObject().put("name", "x").put("taxCategory", "servicios-id"), null);

    assertFalse(body.has("adjustedFields"));
  }

  /**
   * An identifier resolved to an id before the pre-hook is a representation change, not a
   * pre-hook rewrite: the snapshot is taken after it, so it compares equal.
   */
  @Test
  @DisplayName("reconcile: a representation change made before the pre-hook is not reported")
  void aRepresentationChangeBeforeThePreHookIsNotReported() throws Exception {
    JSONObject userProvided = new JSONObject().put("uOM", "Unidad").put("parentId", "p1");
    JSONObject bodyAtDispatch = new JSONObject().put("uOM", "uom-id");

    JSONObject pre = McpWriteRequestSupport.snapshotCallerValues(bodyAtDispatch, userProvided);

    assertEquals(1, pre.length(), "parentId is no longer in the body; uOM is captured as resolved");
    assertEquals("uom-id", pre.getString("uOM"));
    assertFalse(recon(null, pre, new JSONObject().put("uOM", "uom-id"), null)
        .has("adjustedFields"));
  }

  /** B1: governance silences a callout proposal, never a rewrite of what the caller sent. */
  @Test
  @DisplayName("reconcile: a governed field the pre-hook rewrote is reported as adjusted")
  void aGovernedRewrittenFieldIsStillAdjusted() throws Exception {
    JSONObject superseded = new JSONObject().put("stocked", entry(true, "N"));

    JSONObject body = recon(superseded, new JSONObject().put("stocked", true),
        new JSONObject().put("stocked", false), governing("stocked"));

    assertFalse(superseded.has("stocked"));
    JSONObject adjusted = body.getJSONObject("adjustedFields").getJSONObject("stocked");
    assertEquals(true, adjusted.getBoolean("sent"));
    assertEquals(false, adjusted.getBoolean("persisted"));
  }

  @Test
  @DisplayName("reconcile: a governed field that kept its value is dropped, others stay")
  void aGovernedUnchangedFieldIsDropped() throws Exception {
    JSONObject superseded = new JSONObject()
        .put("taxCategory", entry("servicios-id", "normal-id"))
        .put("paymentTerms", entry("30-id", "inmediato-id"));
    JSONObject values = new JSONObject().put("taxCategory", "servicios-id")
        .put("paymentTerms", "30-id");

    JSONObject body = recon(superseded, values, new JSONObject(values.toString()),
        governing("taxCategory"));

    assertFalse(superseded.has("taxCategory"));
    assertTrue(superseded.has("paymentTerms"), "only the governed field is silenced");
    assertFalse(body.has("adjustedFields"));
  }

  /** W2: a key the pre-hook removed has no persisted value. */
  @Test
  @DisplayName("reconcile: a key removed from the final body reports persisted null")
  void aRemovedKeyReportsNullPersisted() throws Exception {
    JSONObject superseded = new JSONObject().put("paymentTerms", entry("30-id", "inmediato-id"));

    JSONObject body = recon(superseded, new JSONObject().put("paymentTerms", "30-id"),
        new JSONObject(), null);

    JSONObject adjusted = body.getJSONObject("adjustedFields").getJSONObject("paymentTerms");
    assertEquals("30-id", adjusted.getString("sent"));
    assertTrue(adjusted.has("persisted"), "the key is present, explicitly null");
    assertTrue(adjusted.isNull("persisted"));
    assertFalse(superseded.has("paymentTerms"));
  }

  /** W3: a final value equal to the sent one is not an adjustment. */
  @Test
  @DisplayName("reconcile: a sent value equal to the final body value is not adjusted")
  void anUnchangedValueIsNotAdjusted() throws Exception {
    JSONObject superseded = new JSONObject().put("paymentTerms", entry("30-id", "inmediato-id"));
    JSONObject values = new JSONObject().put("paymentTerms", "30-id");

    JSONObject body = recon(superseded, values, new JSONObject(values.toString()), null);

    assertFalse(body.has("adjustedFields"));
    assertTrue(superseded.has("paymentTerms"));
  }

  @Test
  @DisplayName("reconcile: 'false' vs false, or 20 vs \"20.00\", are not adjustments")
  void spellingDifferencesAreNotAdjustments() throws Exception {
    JSONObject superseded = new JSONObject().put("stocked", entry("false", "Y"))
        .put("price", entry("20", "25"));

    JSONObject body = recon(superseded,
        new JSONObject().put("stocked", "false").put("price", "20"),
        new JSONObject().put("stocked", false).put("price", "20.00"), null);

    assertEquals(2, superseded.length());
    assertFalse(body.has("adjustedFields"));
  }

  @Test
  @DisplayName("reconcile: nothing to report, no key; a throwing customization never breaks the create")
  void reconcileIsSafe() throws Exception {
    JSONObject body = new JSONObject();
    McpWriteRequestSupport.reconcileSupersededDefaults(body, null, new JSONObject(),
        new JSONObject(), null, null);
    McpWriteRequestSupport.reconcileSupersededDefaults(body, new JSONObject(), new JSONObject(),
        new JSONObject(), null, null);
    assertEquals(0, body.length());

    NeoHandler failing = mock(NeoHandler.class);
    when(failing.governedCreateFields(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("boom"));
    JSONObject superseded = new JSONObject().put("paymentTerms", entry("30-id", "inmediato-id"));
    JSONObject values = new JSONObject().put("paymentTerms", "30-id");
    McpWriteRequestSupport.reconcileSupersededDefaults(body, superseded, values,
        new JSONObject(values.toString()), failing, null);
    assertTrue(superseded.has("paymentTerms"));
  }

  /**
   * The snapshot must be the body the pre-hook receives (after coercion and sentinel fixes) and
   * the reconcile must read the body it left, so: snapshot before dispatch, reconcile after
   * dispatch and before the report.
   */
  @Test
  @DisplayName("handleCreate snapshots before the pre-hook dispatch, reconciles after, reports last")
  void reconcileRunsBetweenDispatchAndReport() {
    String create = McpSourceScanner.methodBody(
        McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleCreate");
    int snapshot = create.indexOf("snapshotCallerValues");
    int dispatch = create.indexOf("NeoExtensionDispatcher.dispatch(customizationRequest)");
    int reconcile = create.indexOf("reconcileSupersededDefaults");
    int report = create.indexOf("reportSupersededDefaults");
    assertTrue(snapshot > 0 && snapshot < dispatch && dispatch < reconcile && reconcile < report,
        "snapshot < pre-hook dispatch < reconcile < report");
    assertFalse(McpSourceScanner.methodBody(
        McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleUpdate")
        .contains("reconcileSupersededDefaults"));
  }

  /**
   * <b>Create only.</b> An update carries no {@code etendo_defaults} invitation and there is no
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
