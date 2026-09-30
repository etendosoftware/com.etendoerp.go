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

package com.etendoerp.go.schemaforge.util;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.enterprise.inject.spi.Bean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.NeoHandler;

/**
 * Memoises what a {@code Java_Qualifier} resolves to, so the two {@link NeoHandler} resolvers stop
 * rescanning CDI on every single call.
 *
 * <h2>Two caches, because there are two resolvers — and they are not equivalent</h2>
 * <p>This module resolves a handler in two different ways, and the difference is deliberate:</p>
 * <dl>
 *   <dt>{@link #beanByName}</dt>
 *   <dd>Backs {@link NeoHandlerLookup#byQualifier}, used by the MCP path and by the access /
 *       report-callability helpers. It matches on {@code Bean#getName()}, the CDI-standard reading
 *       of {@code @Named}, and therefore <b>also sees normal-scoped beans</b>.</dd>
 *   <dt>{@link #handlerClass}</dt>
 *   <dd>Backs {@code NeoServletSupport.lookupHandler}, used by the REST and batch paths. It matches
 *       by reading {@code @Named} off the resolved instance's class, which <b>silently skips any
 *       normal-scoped bean</b> — Weld's client proxy is a subclass and {@code @Named} is not
 *       {@code @Inherited}.</dd>
 * </dl>
 *
 * <p>The two are kept strictly apart here. Sharing one map would quietly make handlers that today
 * do not resolve over REST start resolving — a behaviour change wearing an optimisation's clothes.
 * Converging the two mechanisms is a separate, deliberate decision; this class must not pre-empt
 * it.</p>
 *
 * <h2>What is cached is a deployment fact, not configuration</h2>
 * <p>Both maps hold {@code qualifier -> what CDI has deployed under that name}. That mapping is
 * fixed for the lifetime of the deployment: it is derived from the classes on the classpath and
 * from CDI's bean discovery, never from the database. Editing {@code ETGO_SF_ENTITY} changes
 * <b>which qualifier a request asks for</b> — a different key — it cannot change what an existing
 * key resolves to. So no {@code EntityPersistenceEventObserver} is needed here, unlike
 * {@code McpConfigInvalidationObserver}, which invalidates parsed {@code MCP_CONFIG} precisely
 * because that <i>is</i> operator-edited data. A redeploy replaces the web application classloader
 * and with it this class and its static maps, so a stale entry cannot survive one either.</p>
 *
 * <h2>Lazy, never eager</h2>
 * <p>Entries are populated on first use. Building the maps at startup would need an observer that
 * does not exist in this module, would run into Weld/servlet initialisation ordering, and would
 * open a window in which an empty map reads as "nothing is deployed" — failing open on exactly the
 * question these caches answer.</p>
 *
 * <h2>A miss is not "no handler"</h2>
 * <p>The distinction is kept in the value type: an <b>absent key</b> means "never resolved yet",
 * an {@link Optional#empty()} value means "resolved, and nothing is deployed under this name".
 * Collapsing the two would make it impossible to tell a cold cache from a negative answer, which a
 * later explicit resolution signal will need.</p>
 *
 * <p>A resolver that throws — CDI absent or not yet started — caches nothing and propagates, so a
 * transient failure is never frozen into a negative entry.</p>
 */
public final class NeoHandlerResolutionCache {

  private static final Logger log = LogManager.getLogger(NeoHandlerResolutionCache.class);

  /** {@code qualifier -> Bean} as matched by {@code Bean#getName()}. */
  private static final Map<String, Optional<Bean<?>>> BY_BEAN_NAME = new ConcurrentHashMap<>();

  /** {@code qualifier -> handler class} as matched by {@code @Named} on the instance's class. */
  private static final Map<String, Optional<Class<? extends NeoHandler>>> BY_NAMED_ANNOTATION =
      new ConcurrentHashMap<>();

  private NeoHandlerResolutionCache() {
  }

  /**
   * Read the {@link Bean} deployed under {@code qualifier}, resolving it on a miss.
   *
   * @param qualifier the {@code Java_Qualifier}; {@code null} bypasses the cache
   * @param resolver  computes the answer on a miss; must return {@link Optional#empty()} rather
   *                  than {@code null} when nothing is deployed
   * @return the deployed bean, or {@link Optional#empty()} when there is none
   */
  public static Optional<Bean<?>> beanByName(String qualifier,
      Function<String, Optional<Bean<?>>> resolver) {
    return lookup(BY_BEAN_NAME, "byQualifier", qualifier, resolver);
  }

  /**
   * Read the handler class deployed under {@code qualifier}, resolving it on a miss.
   *
   * <p>The class, not the instance: the callers hand out a fresh CDI reference per request today
   * and must keep doing so, since a shared instance would turn any per-request field into a leak
   * across requests.</p>
   *
   * @param qualifier the {@code Java_Qualifier}; {@code null} bypasses the cache
   * @param resolver  computes the answer on a miss; must return {@link Optional#empty()} rather
   *                  than {@code null} when nothing matches
   * @return the handler class, or {@link Optional#empty()} when nothing matches
   */
  public static Optional<Class<? extends NeoHandler>> handlerClass(String qualifier,
      Function<String, Optional<Class<? extends NeoHandler>>> resolver) {
    return lookup(BY_NAMED_ANNOTATION, "lookupHandler", qualifier, resolver);
  }

  /**
   * Drop every entry of both caches.
   *
   * <p>For tests, and for a caller that deliberately wants the next resolution to go back to CDI.
   * Nothing in normal operation needs it — see the class javadoc on why there is no observer.</p>
   */
  public static void invalidateAll() {
    BY_BEAN_NAME.clear();
    BY_NAMED_ANNOTATION.clear();
    log.debug("Invalidated all NeoHandler resolution caches");
  }

  /**
   * Shared miss path. A {@code null} key is computed directly rather than sharing one slot, which
   * also keeps {@link ConcurrentHashMap} out of its own null-key contract.
   */
  private static <T> Optional<T> lookup(Map<String, Optional<T>> cache, String resolverName,
      String qualifier, Function<String, Optional<T>> resolver) {
    if (qualifier == null) {
      return resolver.apply(null);
    }
    boolean alreadyCached = cache.containsKey(qualifier);
    Optional<T> result = cache.computeIfAbsent(qualifier, resolver);
    logResolution(resolverName, qualifier, alreadyCached, result.isPresent());
    return result;
  }

  /**
   * Temporary resolution trace for the ETP-5415 pilot, emitted at INFO so it is visible in a
   * normal deployment while T0 is being verified by hand.
   *
   * <p>Without it, a cache that memoises nothing is indistinguishable from one that works: the
   * behaviour is identical and only the CDI scan cost differs, so every functional check passes
   * either way. {@code from=SCAN} on a first call followed by {@code from=CACHE} on the second is
   * what makes the memoisation observable from outside.</p>
   *
   * <p>The {@code (debug)} / {@code (warn)} prefix states the level each line is meant to settle
   * at once verification is done. The unresolved branch is the one worth keeping loud: a miss and
   * "no customization is configured for this entity" are the same shape to a caller, which is the
   * silent-failure mode this pilot exists to eliminate. Promoting these lines is the seed of the
   * explicit resolution signal (E5).</p>
   */
  private static void logResolution(String resolverName, String qualifier, boolean alreadyCached,
      boolean resolved) {
    String from = alreadyCached ? "CACHE" : "SCAN";
    if (resolved) {
      log.info("(debug) NeoHandler resolved: resolver={} qualifier={} from={}", resolverName,
          qualifier, from);
    } else {
      log.info("(warn) No NeoHandler deployed: resolver={} qualifier={} from={}", resolverName,
          qualifier, from);
    }
  }
}
