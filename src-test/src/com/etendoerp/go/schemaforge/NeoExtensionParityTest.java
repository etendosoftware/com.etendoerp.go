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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * <b>E4 — THE GATE (ETP-5415, D7).</b> Parity over the <b>execution trace</b>, not the output.
 *
 * <h2>Why the trace and not the response</h2>
 * <p>Output equality cannot see the failure this ticket exists to prevent. §13.1 measured it: two
 * channels can return byte-identical bodies while one of them never reached the customization at
 * all — the generic path simply produced the same answer for that row. The defect only becomes
 * visible on the row where the customization would have changed something, which is exactly the row
 * nobody puts in a fixture. {@code etendo_batch} was switched off
 * because of a divergence of this shape, and was re-enabled once it was closed.</p>
 *
 * <p>So the assertion is on {@code (spec, entity, surface, customization, method, outcome)} as
 * recorded by {@link NeoExtensionTraceRecorder}: every channel must resolve the same customization
 * and invoke the same method. {@code channel} is the one field allowed to differ, because it is the
 * dimension being compared.</p>
 *
 * <h2>Why the resolver mocks are the point, not scaffolding</h2>
 * <p>{@link NeoExtensionDispatcher} deliberately keeps <b>two non-equivalent resolvers</b> — MCP
 * goes through {@link NeoHandlerLookup#byQualifier}, everything else through
 * {@code NeoServletSupport.lookupHandler} — because that difference is current behaviour and
 * collapsing it inside a refactor would have hidden a behaviour change. {@code byQualifier} reads
 * CDI bean metadata and so sees a normal-scoped bean; {@code lookupHandler} reads {@code @Named}
 * off {@code handler.getClass()}, which on a normal-scoped bean is a Weld client proxy that does
 * not carry the (non-{@code @Inherited}) annotation, and silently misses it.
 *
 * <p><b>That is a live divergence, not a hypothetical</b>, and it is the one this gate is for: a
 * handler that resolves over MCP and not over REST produces no error anywhere — both channels
 * answer, one of them just quietly runs generic CRUD. {@link ResolverDivergence} drives the two
 * resolvers apart on purpose and asserts the gate catches it; without that case a green run here
 * would prove only that the mocks agree with each other.</p>
 */
@DisplayName("E4 — a customization reaches every channel identically (ETP-5415, D7)")
class NeoExtensionParityTest {

  private static final String SPEC = "sales-order";
  private static final String ENTITY = "header";
  private static final String QUALIFIER = "salesOrderHeaderHandler";

  /** Query-param key the differing-work stub branches on; stands in for any per-channel input. */
  private static final String CHANNEL_HINT = "channel";

  /** Every channel a write surface is expected to reach. */
  private static final Set<NeoExtensionChannel> ALL_CHANNELS =
      EnumSet.allOf(NeoExtensionChannel.class);

  private MockedStatic<NeoExtensionIndex> index;
  private MockedStatic<NeoHandlerLookup> mcpResolver;
  private MockedStatic<NeoServletSupport> restResolver;

  private NeoHandler customization;
  private NeoResponse preResponse;

  @BeforeEach
  void setUp() {
    customization = mock(NeoHandler.class);
    preResponse = NeoResponse.ok(null);
    when(customization.handle(org.mockito.ArgumentMatchers.any())).thenReturn(preResponse);
    when(customization.afterHandle(org.mockito.ArgumentMatchers.any())).thenReturn(null);

    // The annotation index is empty, as it is in production today: resolution falls through to the
    // qualifier, which is the branch whose per-channel split this test exists to police.
    index = mockStatic(NeoExtensionIndex.class);
    index.when(() -> NeoExtensionIndex.resolve(anyString(), anyString(), anyString()))
        .thenReturn(null);

    mcpResolver = mockStatic(NeoHandlerLookup.class);
    restResolver = mockStatic(NeoServletSupport.class);
    deployOn(ALL_CHANNELS);
  }

  @AfterEach
  void tearDown() {
    NeoExtensionTraceRecorder.stopRecording();
    restResolver.close();
    mcpResolver.close();
    index.close();
  }

  /**
   * Make the customization visible to exactly the given channels' resolvers.
   *
   * <p>This is how a resolver divergence is expressed: "deployed, but only {@code lookupHandler}
   * can see it" is precisely the Weld client-proxy trap, and "only {@code byQualifier} can see it"
   * is its mirror.</p>
   */
  private void deployOn(Set<NeoExtensionChannel> channels) {
    NeoHandler viaMcp = channels.contains(NeoExtensionChannel.MCP) ? customization : null;
    // REST_SINGLE and REST_BATCH share one resolver, so they cannot diverge from each other by
    // construction — asserting it anyway is what keeps that true if the dispatcher changes.
    boolean viaRest = channels.contains(NeoExtensionChannel.REST_SINGLE)
        || channels.contains(NeoExtensionChannel.REST_BATCH);
    mcpResolver.when(() -> NeoHandlerLookup.byQualifier(QUALIFIER)).thenReturn(viaMcp);
    restResolver.when(() -> NeoServletSupport.lookupHandler(QUALIFIER))
        .thenReturn(viaRest ? customization : null);
  }

  private NeoExtensionRequest request(NeoExtensionSurface surface, NeoExtensionChannel channel) {
    return NeoExtensionRequest.builder()
        .qualifier(QUALIFIER)
        .specName(SPEC)
        .entityName(ENTITY)
        .surface(surface)
        .channel(channel)
        .context(NeoContext.builder()
            .specName(SPEC)
            .entityName(ENTITY)
            .httpMethod("POST")
            .endpointType(NeoEndpointType.CRUD)
            .queryParams(java.util.Map.of(CHANNEL_HINT, channel.name()))
            .build())
        .build();
  }

  /**
   * The trace projected onto what parity actually governs: <b>which code was reached, through
   * which entry point</b>.
   *
   * <h3>Why {@code outcome} is deliberately NOT part of this</h3>
   * <p>The invariant is that the logic lives in one place and every channel reaches it — not that
   * every channel produces the same result. Those are different claims, and only the first is
   * required. A customization legitimately does different work per channel: a field the agent
   * omits may be resolved server-side on MCP and left to the caller over REST, so the very same
   * method returns a response on one channel and {@code null} on the other. That is
   * {@code RESOLVED_AND_RAN} versus {@code RESOLVED_RETURNED_NULL} — a behavioural difference the
   * design permits, and asserting on it would make this gate reject correct code.
   *
   * <p>What must never differ is whether the customization was <b>reached</b>, which
   * {@link #reached} captures by collapsing both resolved outcomes into one. The distinction that
   * matters is "the customization decided" versus "the customization was never asked".</p>
   */
  private record Resolution(String specName, String entityName, NeoExtensionSurface surface,
      String qualifier, String customizationClass, String method, boolean reached) {

    static Resolution of(NeoExtensionTrace t) {
      return new Resolution(t.specName(), t.entityName(), t.surface(), t.qualifier(),
          t.customizationClass(), t.method(), reached(t.outcome()));
    }

    /**
     * Whether the customization ran at all. {@code FAILED} counts as reached: it threw, which
     * means its code executed — a failure is a bug in the customization, not a routing gap, and
     * conflating the two would send the next person looking in the wrong place.
     */
    private static boolean reached(NeoExtensionOutcome outcome) {
      return outcome == NeoExtensionOutcome.RESOLVED_AND_RAN
          || outcome == NeoExtensionOutcome.RESOLVED_RETURNED_NULL
          || outcome == NeoExtensionOutcome.FAILED;
    }
  }

  /** Dispatch one surface's full pre+post cycle on one channel and return what was recorded. */
  private List<Resolution> dispatchCycle(NeoExtensionSurface surface,
      NeoExtensionChannel channel) {
    NeoExtensionTraceRecorder.beginRecording();
    NeoExtensionRequest pre = request(surface, channel);
    NeoExtensionResult preResult = NeoExtensionDispatcher.dispatch(pre);
    NeoExtensionDispatcher.dispatch(
        pre.post(preResult.customization()).withPreviousResult(preResult.response()));
    List<Resolution> resolutions = NeoExtensionTraceRecorder.recorded().stream()
        .map(Resolution::of)
        .collect(Collectors.toCollection(ArrayList::new));
    NeoExtensionTraceRecorder.stopRecording();
    return resolutions;
  }

  // ── The gate ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("every channel resolves the same customization and invokes the same methods")
  void parityAcrossChannels() {
    for (NeoExtensionSurface surface : EnumSet.of(NeoExtensionSurface.CREATE,
        NeoExtensionSurface.UPDATE, NeoExtensionSurface.DELETE, NeoExtensionSurface.READ,
        NeoExtensionSurface.SELECTOR, NeoExtensionSurface.ACTION)) {

      Set<List<Resolution>> distinct = new LinkedHashSet<>();
      for (NeoExtensionChannel channel : ALL_CHANNELS) {
        distinct.add(dispatchCycle(surface, channel));
      }

      assertEquals(1, distinct.size(),
          () -> "Channels disagree on " + surface + ". Each line is one channel's resolution "
              + "sequence; they must be identical:\n"
              + distinct.stream().map(Object::toString).collect(Collectors.joining("\n")));
    }
  }

  @Test
  @DisplayName("the trace names the customization — not merely that something ran")
  void traceIdentifiesTheCustomization() {
    List<Resolution> resolutions = dispatchCycle(NeoExtensionSurface.CREATE,
        NeoExtensionChannel.MCP);

    assertEquals(2, resolutions.size(), "pre and post are both recorded");
    Resolution pre = resolutions.get(0);
    assertEquals(SPEC, pre.specName());
    assertEquals(ENTITY, pre.entityName());
    assertEquals(QUALIFIER, pre.qualifier());
    assertEquals("handle", pre.method());
    assertTrue(pre.reached(), "the customization ran, so the trace must say so");
    assertNotNull(pre.customizationClass(),
        "a resolved customization must be named in the trace, or the trace cannot prove parity");
    assertEquals("afterHandle", resolutions.get(1).method());
  }

  @Test
  @DisplayName("no customization deployed traces NO_CUSTOMIZATION on every channel alike")
  void absenceIsAlsoParity() {
    deployOn(EnumSet.noneOf(NeoExtensionChannel.class));

    for (NeoExtensionChannel channel : ALL_CHANNELS) {
      List<Resolution> resolutions = dispatchCycle(NeoExtensionSurface.CREATE, channel);
      assertFalse(resolutions.get(0).reached(),
          () -> "channel " + channel + " must report absence as absence");
      assertNull(resolutions.get(0).customizationClass(),
          "nothing resolved, so nothing may be named");
    }
  }

  @Test
  @DisplayName("the SAME customization may do different work per channel and parity still holds")
  void differentWorkPerChannelIsNotADivergence() {
    // The owner's case, made concrete: a field the agent omits is resolved server-side on MCP,
    // while over REST the caller is expected to send it. One customization, one method, two
    // legitimate answers — a response on MCP, a decline on REST.
    when(customization.handle(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
      NeoContext ctx = invocation.getArgument(0);
      return "MCP".equals(ctx.getQueryParams() == null ? null : ctx.getQueryParams().get(CHANNEL_HINT))
          ? preResponse
          : null;
    });

    List<Resolution> viaMcp = dispatchCycle(NeoExtensionSurface.CREATE, NeoExtensionChannel.MCP);
    List<Resolution> viaRest = dispatchCycle(NeoExtensionSurface.CREATE,
        NeoExtensionChannel.REST_SINGLE);

    assertEquals(viaMcp, viaRest,
        "Both channels reached the same customization through the same method. That the "
            + "customization then answered on one and declined on the other is the design "
            + "working, not a divergence — the invariant is where the logic lives, not that it "
            + "reaches the same conclusion. If this ever fails, the tuple has grown a "
            + "behavioural field and the gate has started rejecting correct code.");
  }

  // ── Proof the gate can fail ─────────────────────────────────────────────

  /**
   * The mutation proof. A parity test whose only evidence is a green run proves nothing: it must be
   * shown to go red on the divergence it exists to catch.
   */
  @Nested
  @DisplayName("the gate catches a real resolver divergence")
  class ResolverDivergence {

    @Test
    @DisplayName("visible to MCP only — the Weld client-proxy trap")
    void visibleToMcpOnly() {
      deployOn(EnumSet.of(NeoExtensionChannel.MCP));

      List<Resolution> viaMcp = dispatchCycle(NeoExtensionSurface.CREATE,
          NeoExtensionChannel.MCP);
      List<Resolution> viaRest = dispatchCycle(NeoExtensionSurface.CREATE,
          NeoExtensionChannel.REST_SINGLE);

      assertTrue(viaMcp.get(0).reached(), "MCP saw the bean");
      assertFalse(viaRest.get(0).reached(), "REST did not — the customization was never asked");
      assertTrue(!viaMcp.equals(viaRest),
          "this is the divergence the gate exists for; if the two are equal the gate is inert");
    }

    @Test
    @DisplayName("visible to REST only — the mirror case")
    void visibleToRestOnly() {
      deployOn(EnumSet.of(NeoExtensionChannel.REST_SINGLE));

      List<Resolution> viaMcp = dispatchCycle(NeoExtensionSurface.CREATE,
          NeoExtensionChannel.MCP);
      List<Resolution> viaRest = dispatchCycle(NeoExtensionSurface.CREATE,
          NeoExtensionChannel.REST_SINGLE);

      assertFalse(viaMcp.get(0).reached(), "MCP did not see the bean");
      assertTrue(viaRest.get(0).reached(), "REST did");
      assertTrue(!viaMcp.equals(viaRest), "the mirror divergence must be caught too");
    }
  }
}
