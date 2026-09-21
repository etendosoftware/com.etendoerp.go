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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openbravo.model.ad.ui.Tab;

/**
 * IMP-45 — a callout that was held back by ETP-4784's protected-fields rule now says so.
 *
 * <h2>The failure being reported</h2>
 * <p>{@code neo_defaults} tells an agent to use its result as the starting point for
 * {@code neo_create}, and {@code neo_create} repeats the advice. Follow it literally and every
 * value handed over becomes a value the caller <em>sent</em>, which ETP-4784 protects from being
 * recomputed by a callout that knows the record's real context. The measured case:
 * {@code neo_defaults(sales-order/header)} answers {@code paymentTerms: "30 Días"} with no business
 * partner in sight, and the partner chosen a moment later implies {@code "Inmediato"} — the agent
 * has pinned the wrong one and the 201 says nothing.</p>
 *
 * <h2>What is asserted, and why the negatives matter most</h2>
 * <p>Recording changes nothing about which value wins: the caller's still does. So the whole value
 * of the feature is that the report is <b>believable</b> — a warning that fires on a callout
 * re-proposing the value already on the record, or on an {@code $_identifier} companion of a field
 * already reported under its own name, is noise the agent must learn to ignore, and then it will
 * ignore the real one too. Hence the no-false-positive cases here outnumber the positive one.</p>
 */
@DisplayName("IMP-45 — supersededDefaults is recorded where the callout is held back")
class NeoSupersededDefaultsTest {

  private static final String PAYMENT_TERMS = "paymentTerms";
  private static final String KEPT = "30-dias-id";
  private static final String DERIVED = "inmediato-id";

  /**
   * Drive one pass of the cascade's update merge.
   *
   * <p>Reflection because the method is private and needs no seam of its own: the divergence is
   * recorded at the exact point {@code shouldKeepExistingValue} holds the callout back, and any
   * test that reached it through the public cascade would need a live callout, an
   * {@code AD_Tab} and a DAL. Everything downstream of the protected branch is short-circuited by
   * the fixtures, so {@code adTab} is never dereferenced.</p>
   *
   * @param defaults        the in-progress payload, holding the caller's values
   * @param updates         the callout's proposed updates, as {@code {field: {value: …}}}
   * @param protectedFields the fields ETP-4784 protects — the ones the caller sent
   * @return the cascade result, carrying whatever divergences were recorded
   */
  private static NeoDefaultsService.CalloutCascadeResult merge(JSONObject defaults,
      JSONObject updates, Set<String> protectedFields) throws Exception {
    JSONObject calloutBody = new JSONObject();
    calloutBody.put("updates", updates);

    NeoDefaultsService.CalloutCascadeResult result =
        new NeoDefaultsService.CalloutCascadeResult();
    Method merge = NeoDefaultsCascadeHelper.class.getDeclaredMethod("mergeCalloutUpdates",
        JSONObject.class, JSONObject.class, JSONObject.class, Set.class, Tab.class,
        NeoDefaultsService.CalloutCascadeResult.class, Set.class, Set.class, Set.class);
    merge.setAccessible(true);
    // seqFields holds every key in play so the unprotected branch short-circuits before it would
    // resolve a callout against the (absent) AD_Tab — this test is about the protected branch.
    Set<String> seqFields = new HashSet<>();
    java.util.Iterator<String> keys = updates.keys();
    while (keys.hasNext()) {
      seqFields.add(keys.next());
    }
    merge.invoke(null, calloutBody, new JSONObject(), defaults, seqFields, null, result,
        new HashSet<>(), protectedFields, Collections.emptySet());
    return result;
  }

  private static JSONObject update(String field, Object value) throws Exception {
    JSONObject updates = new JSONObject();
    updates.put(field, new JSONObject().put("value", value));
    return updates;
  }

  // ── the positive case ─────────────────────────────────────────────────

  @Test
  @DisplayName("a callout held back by a protected field is recorded with both values")
  void aHeldBackCalloutIsRecorded() throws Exception {
    JSONObject defaults = new JSONObject().put(PAYMENT_TERMS, KEPT);

    JSONObject superseded = merge(defaults, update(PAYMENT_TERMS, DERIVED),
        Set.of(PAYMENT_TERMS)).getSupersededDefaults();

    assertEquals(1, superseded.length());
    JSONObject entry = superseded.getJSONObject(PAYMENT_TERMS);
    assertEquals(KEPT, entry.getString("sent"),
        "'sent' is the value that stayed on the record — the caller's");
    assertEquals(DERIVED, entry.getString("callout"),
        "'callout' is what the record's own context implied, which is the news");
  }

  @Test
  @DisplayName("the caller's value still wins — recording changes nothing about the write")
  void theCallersValueStillWins() throws Exception {
    JSONObject defaults = new JSONObject().put(PAYMENT_TERMS, KEPT);

    merge(defaults, update(PAYMENT_TERMS, DERIVED), Set.of(PAYMENT_TERMS));

    assertEquals(KEPT, defaults.getString(PAYMENT_TERMS),
        "on this path the server cannot tell an echoed default from a value a human chose, so"
            + " silently replacing the second would be a harder failure than reporting the first");
  }

  // ── the no-false-positive rules ───────────────────────────────────────

  /**
   * A callout re-proposing the value already on the record has superseded nothing. This is the
   * common case on every create — most callouts fire and agree — so a report here would bury the
   * one that matters.
   */
  @Test
  @DisplayName("a callout re-proposing the same value records nothing")
  void agreementIsNotADivergence() throws Exception {
    JSONObject defaults = new JSONObject().put(PAYMENT_TERMS, KEPT);

    assertEquals(0, merge(defaults, update(PAYMENT_TERMS, KEPT), Set.of(PAYMENT_TERMS))
        .getSupersededDefaults().length());
  }

  /**
   * The companion key is the label of a field already reported under its own name, so reporting it
   * would double every entry and invite the agent to "fix" a key it never sends.
   */
  @Test
  @DisplayName("an $_identifier companion is skipped")
  void identifierCompanionsAreSkipped() throws Exception {
    String companion = PAYMENT_TERMS + "$_identifier";
    JSONObject defaults = new JSONObject().put(companion, "30 Días");

    assertEquals(0, merge(defaults, update(companion, "Inmediato"), Set.of(companion))
        .getSupersededDefaults().length());
  }

  @Test
  @DisplayName("a callout proposing null or JSON null records nothing")
  void anEmptyProposalIsNotADivergence() throws Exception {
    JSONObject defaults = new JSONObject().put(PAYMENT_TERMS, KEPT);

    assertEquals(0, merge(defaults, update(PAYMENT_TERMS, JSONObject.NULL), Set.of(PAYMENT_TERMS))
        .getSupersededDefaults().length());
  }

  /**
   * An unprotected field is one the caller did not send, so the callout's value is simply applied.
   * Nothing was superseded and nothing may be reported — otherwise every ordinary cascade update
   * would come back as a warning.
   */
  @Test
  @DisplayName("an unprotected field the callout is free to write records nothing")
  void anAppliedUpdateIsNotADivergence() throws Exception {
    JSONObject defaults = new JSONObject();

    NeoDefaultsService.CalloutCascadeResult result =
        merge(defaults, update(PAYMENT_TERMS, DERIVED), Set.of());

    assertEquals(0, result.getSupersededDefaults().length());
    assertEquals(DERIVED, defaults.getString(PAYMENT_TERMS), "the callout's value was applied");
  }

  /**
   * A protected field whose current value is blank is not really held back — ETP-4784's rule
   * treats it as absent and lets the callout through — so there is nothing to report either.
   */
  @Test
  @DisplayName("a protected field holding a blank value is not a divergence")
  void aBlankProtectedValueIsNotADivergence() throws Exception {
    JSONObject defaults = new JSONObject().put(PAYMENT_TERMS, "   ");

    assertEquals(0, merge(defaults, update(PAYMENT_TERMS, DERIVED), Set.of(PAYMENT_TERMS))
        .getSupersededDefaults().length());
  }

  @Test
  @DisplayName("a cascade with no divergence hands back an empty object, never null")
  void theResultIsAlwaysPresent() {
    assertEquals(0,
        new NeoDefaultsService.CalloutCascadeResult().getSupersededDefaults().length());
  }

  // ── the channel to the caller ─────────────────────────────────────────

  /**
   * {@code NeoContext} is how the divergence leaves the cascade. It is mutable and off the builder
   * on purpose — it is produced deep inside the create, after the context was built — so the only
   * thing to pin is that an untouched context reports nothing rather than an empty object a caller
   * would have to distinguish.
   */
  @Test
  @DisplayName("NeoContext carries the divergences, and reports none by default")
  void theContextCarriesIt() throws Exception {
    NeoContext ctx = NeoContext.builder()
        .specName("sales-order").entityName("header")
        .httpMethod("POST").endpointType(NeoEndpointType.CRUD).build();

    assertNull(ctx.getSupersededDefaults(),
        "a create where nothing diverged must leave the key off the response entirely");

    JSONObject recorded = new JSONObject().put(PAYMENT_TERMS,
        new JSONObject().put("sent", KEPT).put("callout", DERIVED));
    ctx.setSupersededDefaults(recorded);
    assertEquals(recorded, ctx.getSupersededDefaults());
  }

  /**
   * <b>The REST path must not read it, and that is a decision rather than an oversight.</b> There
   * the protected value came from a form a person filled in, so there is nothing to warn about;
   * the MCP path is the one that invites an agent to re-send what {@code neo_defaults} handed it.
   * A reader added in {@code NeoCrudHandler} would put an agent-facing diagnostic into every React
   * create response.
   */
  @Test
  @DisplayName("the REST create path does not read supersededDefaults")
  void theRestPathIgnoresIt() throws IOException {
    String crudHandler = readModuleSource("com/etendoerp/go/schemaforge/NeoCrudHandler.java");
    assertTrue(crudHandler.contains("executeCalloutCascade"),
        "NeoCrudHandler no longer runs the cascade at all — fix this test only after checking why");
    assertFalse(crudHandler.contains("upersededDefault"),
        "the REST create path now reads the IMP-45 diagnostic, which puts an agent-facing warning"
            + " on every React create response");
  }

  /** Read a module source file, from the Etendo root or from anywhere under it. */
  private static String readModuleSource(String relativePath) throws IOException {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve("modules/com.etendoerp.go/src").resolve(relativePath);
      if (Files.exists(candidate)) {
        return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
      }
      Path local = dir.resolve("src").resolve(relativePath);
      if (Files.exists(local)) {
        return new String(Files.readAllBytes(local), StandardCharsets.UTF_8);
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("Source not found: " + relativePath);
  }
}
