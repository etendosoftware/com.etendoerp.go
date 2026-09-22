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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * The single entry point through which a write path reaches an entity's customization.
 *
 * <h2>What this class is for (ETP-5415, T1)</h2>
 * <p>Today every path hand-rolls the same three steps — resolve a {@code Java_Qualifier}, invoke
 * {@code handle} or {@code afterHandle}, interpret {@code null} — and they do it differently: the
 * REST dispatcher in {@code NeoServletSupport.handleWithHooks}, the batch path through the same
 * method, and six separate sites in {@code McpToolRouter}. The consequence is not duplication, it
 * is invisibility: "no customization is configured", "the customization declined" and "the lookup
 * failed" all arrive at the caller as {@code null} and produce the same generic-CRUD response, so
 * a handler that silently stopped resolving looks exactly like one that had nothing to do. This
 * class makes that difference observable — see {@link NeoExtensionOutcome} — and does nothing
 * else.</p>
 *
 * <h2>What it deliberately does NOT do</h2>
 * <ul>
 *   <li><b>It does not merge the two resolvers.</b> {@code NeoServletSupport.lookupHandler} and
 *       {@link NeoHandlerLookup#byQualifier} are not equivalent (the second sees normal-scoped
 *       beans, the first silently skips them), and which one a path uses is current behaviour.
 *       {@link #resolve} picks by {@link NeoExtensionChannel}, exactly as the caller did.</li>
 *   <li><b>It does not normalise the paths.</b> Hook ordering, null guards, error short-circuiting
 *       and the audit-token refresh stay in the callers, differences and all. Choosing between
 *       those behaviours is a later, deliberate step; doing it here would hide a behaviour change
 *       inside a refactor.</li>
 *   <li><b>It does not swallow anything.</b> A resolver or a customization that throws propagates
 *       untouched, so each caller's existing {@code catch} keeps deciding what a failure means.
 *       The only addition is a {@link NeoExtensionOutcome#FAILED} trace before the rethrow.</li>
 * </ul>
 *
 * <h2>Resolution order (ETP-5415, A13)</h2>
 * <p>{@link #resolve} now asks {@link NeoExtensionIndex} — the {@link NeoExtension} annotation on
 * the class itself — before falling back to the {@code Java_Qualifier}. The fallback is untouched,
 * and while no class carries the annotation the index is empty, so every lookup misses and the
 * behaviour is identical to before.</p>
 *
 * <p><b>Package.</b> This lives in {@code com.etendoerp.go.schemaforge} rather than a package of
 * its own because {@code NeoServletSupport} — and its {@code lookupHandler} — is package-private.
 * A dedicated package would have forced widening that class's visibility, which is a change to the
 * surface this step is supposed to leave alone.</p>
 */
public final class NeoExtensionDispatcher {

  private static final Logger log = LogManager.getLogger(NeoExtensionDispatcher.class);

  private NeoExtensionDispatcher() {
  }

  /**
   * Resolve (pre phase only) and invoke the customization for one operation, recording what
   * happened.
   *
   * @param request what to dispatch; a {@link NeoExtensionRequest.Phase#POST} request must
   *                carry the instance its pre phase resolved
   * @return the resolved customization, its response, and the trace
   */
  public static NeoExtensionResult dispatch(NeoExtensionRequest request) {
    NeoHandler customization = request.phase() == NeoExtensionRequest.Phase.POST
        ? request.customization()
        : resolve(request);

    if (customization == null) {
      return finish(request, null, null, NeoExtensionOutcome.NO_CUSTOMIZATION);
    }

    NeoResponse response;
    try {
      response = invoke(request, customization);
    } catch (RuntimeException e) {
      finish(request, customization, null, NeoExtensionOutcome.FAILED);
      throw e;
    }

    return finish(request, customization, response, response != null
        ? NeoExtensionOutcome.RESOLVED_AND_RAN
        : NeoExtensionOutcome.RESOLVED_RETURNED_NULL);
  }

  /**
   * Invoke the phase's method. The post phase sets {@code previousResult} first and does so
   * unconditionally — including when it is {@code null}, which is what the REST path passes when
   * the default CRUD service returned nothing.
   */
  private static NeoResponse invoke(NeoExtensionRequest request, NeoHandler customization) {
    if (request.phase() == NeoExtensionRequest.Phase.POST) {
      request.context().setPreviousResult(request.previousResult());
      return customization.afterHandle(request.context());
    }
    return customization.handle(request.context());
  }

  /**
   * Resolve without invoking anything, for a surface that only has a post-hook.
   *
   * <p>{@code neo_defaults} never runs a pre-hook: it calls {@code afterHandle} over a response
   * the generic service already produced. Such a caller still needs the resolution order — and
   * without this entry point it would have to reach for {@code NeoHandlerLookup} directly and
   * would then miss every annotated class, which is precisely the half-wired state this ticket
   * exists to remove.</p>
   *
   * @param request a PRE-phase request describing the entity; nothing is invoked
   * @return the customization, or {@code null} when the entity has none
   */
  public static NeoHandler resolveOnly(NeoExtensionRequest request) {
    return resolve(request);
  }

  /**
   * Resolve the customization: the {@link NeoExtension} annotation first, the
   * {@code Java_Qualifier} second.
   *
   * <p>This is the single place the two mechanisms meet, and the order is the whole of the
   * change. A class that declares the {@code (spec, entity)} in source wins; when none does —
   * which is every case today, since no class carries the annotation yet — the lookup misses and
   * control falls through to {@link #resolveByQualifier}, byte for byte the behaviour that was
   * here before. That is what makes this step additive: with an empty index the two branches are
   * the same branch.</p>
   *
   * <p>The annotation branch is deliberately <b>not</b> split by {@link NeoExtensionChannel}. The
   * channel split below exists to preserve two resolvers that are already not equivalent and whose
   * difference is current behaviour; the annotation is new, so there is no divergence to preserve,
   * and introducing one would mean a handler that resolves over MCP but not over REST — the exact
   * class of silent failure this pilot exists to remove.</p>
   */
  private static NeoHandler resolve(NeoExtensionRequest request) {
    NeoHandler annotated = NeoExtensionIndex.resolve(request.specName(), request.entityName(),
        request.qualifier());
    if (annotated != null) {
      return annotated;
    }
    return resolveByQualifier(request);
  }

  /**
   * The fallback: pick the resolver the channel's callers use today. The two are not
   * interchangeable; see the class javadoc and {@code NeoHandlerResolutionCache}.
   */
  private static NeoHandler resolveByQualifier(NeoExtensionRequest request) {
    if (request.channel() == NeoExtensionChannel.MCP) {
      return NeoHandlerLookup.byQualifier(request.qualifier());
    }
    return NeoServletSupport.lookupHandler(request.qualifier());
  }

  /** Build the trace, record it, log it, and wrap it in the result. */
  private static NeoExtensionResult finish(NeoExtensionRequest request,
      NeoHandler customization, NeoResponse response, NeoExtensionOutcome outcome) {
    NeoExtensionTrace trace = new NeoExtensionTrace(request.specName(),
        request.entityName(), request.surface(), request.channel(), request.qualifier(),
        customization == null ? null : customization.getClass().getName(),
        customization == null ? null : request.phase().methodName(), outcome);
    NeoExtensionTraceRecorder.record(trace);
    logTrace(trace);
    return new NeoExtensionResult(customization, response, trace);
  }

  /**
   * Resolution trace for the ETP-5415 pilot, emitted at INFO so it is visible in a normal
   * deployment while the pilot is being verified by hand, and carrying the {@code (debug)} /
   * {@code (warn)} prefix that states the level each line is meant to settle at — the same
   * convention {@code NeoHandlerResolutionCache.logResolution} already uses.
   *
   * <p>{@link NeoExtensionOutcome#NO_CUSTOMIZATION} is the loud one on purpose: it is the case
   * that is indistinguishable from a working dispatch everywhere else in the system.</p>
   */
  private static void logTrace(NeoExtensionTrace trace) {
    String prefix = trace.outcome() == NeoExtensionOutcome.RESOLVED_AND_RAN
        || trace.outcome() == NeoExtensionOutcome.RESOLVED_RETURNED_NULL ? "(debug)" : "(warn)";
    log.info(
        "{} NeoExtension dispatched: spec={} entity={} surface={} channel={} qualifier={} "
            + "customization={} method={} outcome={}",
        prefix, trace.specName(), trace.entityName(), trace.surface(), trace.channel(),
        trace.qualifier(), trace.customizationClass(), trace.method(), trace.outcome());
  }
}
