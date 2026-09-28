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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>E4, structural half (ETP-5415, D7).</b> Every surface MCP exposes must actually dispatch.
 *
 * <h2>What this catches that the trace test cannot</h2>
 * <p>{@code NeoExtensionParityTest} proves the dispatcher treats the channels identically <i>once
 * it is called</i>. It cannot prove a channel calls it at all — and that was the real defect:
 * {@code SELECTOR} reached a customization over REST and over no other channel, because
 * {@code handleSelectors} simply had no dispatch in it. The candidate list an agent received was
 * quietly the unfiltered one, with nothing in any response or log to say a hook had been skipped.
 * Before that was fixed, {@code NeoExtensionSurface.SELECTOR} appeared nowhere in this package;
 * this assertion is what makes the omission loud instead of invisible.</p>
 *
 * <h2>Why the two channels are asserted differently</h2>
 * <p>They declare the surface differently, and both ways are correct. MCP names it as a literal
 * per call site, because the router knows which tool it is serving. REST derives it from the
 * request with {@code NeoExtensionSurface.of(context)}, whose mapping is total — so there is no
 * literal to look for, and demanding one would be demanding the worse design. What REST is asserted
 * on instead is that its dispatch goes through {@link
 * com.etendoerp.go.schemaforge.NeoExtensionDispatcher} at all.</p>
 *
 * <h2>What this does NOT prove</h2>
 * <p>That the dispatch is reached at runtime, or placed correctly relative to its surroundings. A
 * call site can exist and sit behind a guard that never fires. This is a source-shape guard — the
 * cheap half — and it is paired with, not a substitute for, the trace parity in
 * {@code NeoExtensionParityTest} and the live verification recorded in the plan (§6.0.11, §6.0.13).
 * </p>
 */
@DisplayName("E4 (structural) — every MCP surface dispatches, and REST dispatches at all")
class NeoExtensionSurfaceCoverageTest {

  private static final String MCP_ROUTER = "com/etendoerp/go/mcp/McpToolRouter.java";
  private static final String MCP_HOOKS = "com/etendoerp/go/mcp/McpHookExecutor.java";
  private static final String REST_SUB_ENDPOINTS =
      "com/etendoerp/go/schemaforge/NeoHookDispatcher.java";
  private static final String REST_CRUD = "com/etendoerp/go/schemaforge/NeoServletSupport.java";
  private static final String REST_BATCH = "com/etendoerp/go/schemaforge/BatchService.java";

  /**
   * The surfaces MCP serves. Deliberately a hand-written list rather than every enum constant:
   * {@code CALLOUT} and {@code EVALUATE_DISPLAY} are REST-only (no MCP tool exposes them) and
   * {@code UNKNOWN} is the fallback for a site that identifies no operation, so asserting the whole
   * enum would demand dispatches that should not exist. Adding a tool for one of those surfaces
   * means adding it here — which is the point: the list is the contract.
   */
  private static final List<String> MCP_SURFACES =
      List.of("CREATE", "UPDATE", "DELETE", "READ", "DEFAULTS", "ACTION", "SELECTOR");

  private static final Pattern SURFACE_LITERAL =
      Pattern.compile("NeoExtensionSurface\\.([A-Z_]+)");

  @Test
  @DisplayName("MCP dispatches every surface it exposes")
  void mcpCoversEverySurface() {
    Set<String> dispatched = surfaceLiterals(
        McpSourceScanner.read(MCP_ROUTER) + McpSourceScanner.read(MCP_HOOKS));

    List<String> missing = MCP_SURFACES.stream()
        .filter(surface -> !dispatched.contains(surface))
        .collect(Collectors.toList());

    assertTrue(missing.isEmpty(),
        () -> "MCP exposes these surfaces but never dispatches them: " + missing
            + ". A surface with no dispatch reaches no customization, and nothing in the response "
            + "or the log says so — see the SELECTOR case in this class's javadoc. Add the "
            + "dispatch, or remove the surface from MCP_SURFACES if the tool no longer exposes it."
            + " Found: " + dispatched);
  }

  @Test
  @DisplayName("every REST dispatch site routes through the one dispatcher")
  void restRoutesThroughTheDispatcher() {
    for (String source : List.of(REST_SUB_ENDPOINTS, REST_CRUD, REST_BATCH)) {
      String body = McpSourceScanner.read(source);
      // Either dispatching directly, or delegating to the one method that does.
      // BatchService takes the second route on purpose: NeoServletSupport.handleWithHooks is the
      // shared runner for every REST write, and routing batch through it is what keeps batch and
      // the direct HTTP write on one path after they had already silently diverged once
      // (ETP-4254). Demanding a literal NeoExtensionDispatcher here would be demanding that batch
      // hand-roll its own dispatch — the exact shape this assertion exists to prevent.
      assertTrue(body.contains("NeoExtensionDispatcher") || body.contains("handleWithHooks"),
          () -> source + " dispatches a hook without going through NeoExtensionDispatcher, "
              + "directly or via NeoServletSupport.handleWithHooks. A third path means a third "
              + "resolution order and no trace — which is how 'the customization declined' and "
              + "'nothing was resolved' became the same observation (ETP-5415, §6.0.7).");
    }
  }

  @Test
  @DisplayName("each channel is declared exactly where it is dispatched from")
  void channelsAreDeclaredAtTheirOwnSites() {
    assertTrue(McpSourceScanner.read(MCP_ROUTER).contains("NeoExtensionChannel.MCP")
            || McpSourceScanner.read(MCP_HOOKS).contains("NeoExtensionChannel.MCP"),
        "the MCP router must dispatch on the MCP channel");
    assertTrue(McpSourceScanner.read(REST_SUB_ENDPOINTS).contains("REST_SINGLE"),
        "REST sub-endpoints must dispatch on REST_SINGLE");
    assertTrue(McpSourceScanner.read(REST_CRUD).contains("REST_SINGLE"),
        "REST CRUD must dispatch on REST_SINGLE");
    assertTrue(McpSourceScanner.read(REST_BATCH).contains("REST_BATCH"),
        "the batch service must dispatch on REST_BATCH — the channel whose divergence from "
            + "neo_create had BATCH_TOOL_ENABLED off until ETP-5415 converged them");
  }

  private static Set<String> surfaceLiterals(String source) {
    Set<String> found = new LinkedHashSet<>();
    Matcher matcher = SURFACE_LITERAL.matcher(source);
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }
}
