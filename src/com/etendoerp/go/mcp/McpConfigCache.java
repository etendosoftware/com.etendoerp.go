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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * The two caches behind {@link McpEntityConfig}, kept apart because what they hold has different
 * lifecycles.
 *
 * <p><b>Why {@code expireAfterAccess} and not {@code expireAfterWrite}.</b> The other two Guava
 * caches in this module ({@code NeoSessionVarsCache}, {@code FinancialAccountCountrySupport}) use
 * {@code expireAfterWrite}, which expires an entry a fixed time after it was loaded even while it
 * is being hit — a forced periodic reload. What is wanted here is the opposite: entries that stop
 * being used should be released, and entries under load should stay. That is
 * {@code expireAfterAccess}.</p>
 *
 * <h2>The two caches</h2>
 * <dl>
 *   <dt>{@link #parsedConfig}</dt>
 *   <dd>Keyed by SchemaForge record id. Saves re-parsing the {@code MCP_CONFIG} text — cheap per
 *       call but very frequent. Changes whenever <b>a user edits the configuration</b>, so it needs
 *       both the invalidation observer and a freshness ceiling (see below).</dd>
 *   <dt>{@link #resolvedHierarchy}</dt>
 *   <dd>Keyed by {@code AD_Tab} id. Saves {@code KernelUtils.getParentTab()}, which runs
 *       <b>an SQL query</b> through {@code KernelUtilsData}, plus the DAL property resolution and
 *       FK selection on top. This is the one that actually costs something. It holds application
 *       dictionary data, which only changes with an {@code update.database} — and that restarts
 *       Tomcat — so it can afford a much longer TTL.</dd>
 * </dl>
 *
 * <p>Splitting them is what lets the dictionary cache keep a long TTL without risking stale
 * configuration: what an operator edits lives in the first one, not the second. Core draws the same
 * distinction with {@code ApplicationDictionaryCachedStructures}.</p>
 *
 * <h2>Why the config cache also has a write ceiling</h2>
 * <p>With {@code expireAfterAccess} alone a frequently-read entry <b>never refreshes</b>: edit
 * {@code MCP_CONFIG} in the window and the change stays invisible until the entity goes unused for
 * the whole access TTL. {@link McpConfigInvalidationObserver} is the fix — it drops the entry on
 * save — but it only fires for changes that go through the DAL. A dataset import, a modulescript or
 * a direct {@code UPDATE} bypasses it, and for a column that can be gating access, "eventually" is
 * not good enough. {@link #CONFIG_WRITE_TTL_HOURS} bounds how stale an entry can get on those
 * paths. The observer is the correctness; this is the insurance.</p>
 *
 * <h2>Nothing session-dependent belongs here</h2>
 * <p>Both caches are {@code static} and shared across every request in the JVM, so a value that
 * varied with the caller would leak across roles, organizations and clients. The invariant that
 * makes them safe: <b>keys are record ids or {@code AD_Tab} ids — never names — and values must not
 * depend on {@code OBContext}</b>. A record's {@code MCP_CONFIG} is the same text for anyone
 * entitled to read it, and a tab's hierarchy is dictionary data identical for everyone, so both
 * hold. A future section needing a role-dependent answer must not resolve it through this class
 * unless the role becomes part of the key.</p>
 */
final class McpConfigCache {

  private static final Logger log = LogManager.getLogger(McpConfigCache.class);

  /** Entries per cache. 160 entities are exposed today; roomy enough for all three levels. */
  private static final long MAX_ENTRIES = 500;

  /** Inactivity TTL for parsed configuration — an unused entry is released after this. */
  static final long CONFIG_ACCESS_TTL_MINUTES = 30;

  /** Freshness ceiling for parsed configuration, for changes the observer cannot see. */
  static final long CONFIG_WRITE_TTL_HOURS = 2;

  /** Inactivity TTL for resolved hierarchy. Long: the dictionary does not change hot. */
  static final long HIERARCHY_ACCESS_TTL_HOURS = 2;

  private static final Cache<String, Object> PARSED_CONFIG = CacheBuilder.newBuilder()
      .maximumSize(MAX_ENTRIES)
      .expireAfterAccess(CONFIG_ACCESS_TTL_MINUTES, TimeUnit.MINUTES)
      .expireAfterWrite(CONFIG_WRITE_TTL_HOURS, TimeUnit.HOURS)
      .recordStats()
      .build();

  private static final Cache<String, Object> RESOLVED_HIERARCHY = CacheBuilder.newBuilder()
      .maximumSize(MAX_ENTRIES)
      .expireAfterAccess(HIERARCHY_ACCESS_TTL_HOURS, TimeUnit.HOURS)
      .recordStats()
      .build();

  private McpConfigCache() {
  }

  /**
   * Read the parsed configuration of one SchemaForge record, loading it on a miss.
   *
   * @param recordId the {@code ETGO_SF_SPEC}/{@code ETGO_SF_ENTITY}/{@code ETGO_SF_FIELD} id
   * @param loader   computes the value on a miss; must not return {@code null}
   * @param <T>      the cached value's type, as the caller knows it
   * @return the cached or freshly loaded value
   */
  @SuppressWarnings("unchecked")
  static <T> T parsedConfig(String recordId, Callable<T> loader) {
    return (T) get(PARSED_CONFIG, recordId, (Callable<Object>) loader, "parsedConfig");
  }

  /**
   * Read the resolved hierarchy of one tab, loading it on a miss.
   *
   * @param tabId  the {@code AD_Tab} id
   * @param loader computes the value on a miss; must not return {@code null}
   * @param <T>    the cached value's type, as the caller knows it
   * @return the cached or freshly loaded value
   */
  @SuppressWarnings("unchecked")
  static <T> T resolvedHierarchy(String tabId, Callable<T> loader) {
    return (T) get(RESOLVED_HIERARCHY, tabId, (Callable<Object>) loader, "resolvedHierarchy");
  }

  /**
   * Shared miss path. A loader that fails is not cached and its cause is rethrown unwrapped, so the
   * caller sees the same failure it would have seen computing the value itself — a cache must not
   * change how errors surface.
   */
  private static Object get(Cache<String, Object> cache, String key, Callable<Object> loader,
      String which) {
    if (key == null) {
      // No key means nothing to cache against; compute directly rather than share one null slot.
      return call(loader);
    }
    try {
      return cache.get(key, loader);
    } catch (ExecutionException | com.google.common.util.concurrent.UncheckedExecutionException e) {
      log.debug("{} loader failed for key {}", which, key);
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new IllegalStateException(cause == null ? e : cause);
    }
  }

  private static Object call(Callable<Object> loader) {
    try {
      return loader.call();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Drop one record's parsed configuration. Called by {@link McpConfigInvalidationObserver} when
   * that record is saved, updated or deleted.
   *
   * @param recordId the record whose entry to drop; a {@code null} or unknown id is a no-op
   */
  static void invalidateConfig(String recordId) {
    if (recordId != null) {
      PARSED_CONFIG.invalidate(recordId);
      log.debug("Invalidated MCP_CONFIG cache entry for {}", recordId);
    }
  }

  /**
   * Drop every entry of both caches.
   *
   * <p>Intended for tests and for the deploy-time validator, which reads every configuration and
   * should not leave a warm cache built from a pre-validation state.</p>
   */
  static void invalidateAll() {
    PARSED_CONFIG.invalidateAll();
    RESOLVED_HIERARCHY.invalidateAll();
    log.debug("Invalidated all MCP config caches");
  }

  /**
   * Emit hit rate and size for both caches at debug level.
   *
   * <p>The TTLs above are judgement calls, not measurements. This is what makes them tunable
   * against real agent traffic instead of guesswork.</p>
   */
  static void logStats() {
    if (log.isDebugEnabled()) {
      log.debug("MCP config cache — parsedConfig: size={} {} | resolvedHierarchy: size={} {}",
          PARSED_CONFIG.size(), PARSED_CONFIG.stats(),
          RESOLVED_HIERARCHY.size(), RESOLVED_HIERARCHY.stats());
    }
  }
}
