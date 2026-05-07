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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.ConfigParameters;
import org.openbravo.base.secureApp.LoginUtils;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.service.db.DalConnectionProvider;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Cache for the immutable session-vars payload shared by callouts, defaults,
 * and selectors.
 *
 * <p>Selecting a Product on an order line cascades through ~5 callouts. Each
 * one used to call {@link LoginUtils#fillSessionArguments} (multiple DB
 * queries) and {@link LoginUtils#readNumberFormat} (parses {@code Format.xml}).
 * Profiling showed the cumulative cost dominated the request — ~1.2s out of
 * a ~1.8s product-callout cascade.</p>
 *
 * <p>This cache stores an immutable {@link Map} of the session attributes
 * produced by those two calls, keyed by
 * {@code userId|roleId|clientId|orgId|warehouseId|lang}. Callers rebuild a
 * fresh {@link VariablesSecureApp} per request and replay the cached entries —
 * cheap (HashMap puts) and lets each request set per-tab attributes such as
 * {@code IsSOTrx} on top without leaking between windows.</p>
 *
 * <p>Replaces the previous {@code NeoDefaultsService.varsCache} which kept
 * mutable {@code VariablesSecureApp} instances and had a latent
 * {@code IsSOTrx} leak across windows.</p>
 */
public final class NeoSessionVarsCache {

  private static final Logger log = LogManager.getLogger(NeoSessionVarsCache.class);

  /**
   * Session keys captured in the cached snapshot. Mirror of the list in
   * {@code NeoCalloutService.buildSessionAttributes} — anything outside this
   * list would be unreachable to a callout anyway, since the
   * {@code SyntheticHttpServletRequest} only carries what
   * {@code buildSessionAttributes} exposes.
   */
  private static final String[] IDENTITY_KEYS = {
      "#AD_User_ID",
      "#AD_Role_ID",
      "#AD_Client_ID",
      "#AD_Org_ID",
      "#M_Warehouse_ID",
      "#AD_Language",
      "#AD_Session_ID",
      "#User_Client",
      "#AD_JavaDateFormat",
      "#AD_JavaDateTimeFormat",
  };

  /**
   * Number-format names that get a {@code #GroupSeparator|<name>},
   * {@code #DecimalSeparator|<name>} and {@code #FormatOutput|<name>} entry
   * each, populated by {@link LoginUtils#readNumberFormat}.
   */
  private static final String[] FORMAT_NAMES = {
      "qtyEdition", "euroEdition", "priceEdition", "integerEdition",
      "generalQtyEdition", "euroInform"
  };

  private static final Cache<String, Map<String, String>> CACHE = CacheBuilder.newBuilder()
      .maximumSize(200)
      .expireAfterWrite(5, TimeUnit.MINUTES)
      .build();

  private NeoSessionVarsCache() {
  }

  /**
   * Return the cached session-vars snapshot for the given identity, loading it
   * synchronously on miss. The returned map is unmodifiable and safe to share
   * across threads.
   *
   * <p>Callers should NOT mutate the returned map. To produce a
   * {@link VariablesSecureApp} for downstream use, replay the entries into a
   * fresh instance via {@link #replayInto(VariablesSecureApp, Map)}.</p>
   */
  public static Map<String, String> getOrLoad(String userId, String roleId,
      String clientId, String orgId, String warehouseId, String lang) {
    String key = buildKey(userId, roleId, clientId, orgId, warehouseId, lang);
    Map<String, String> cached = CACHE.getIfPresent(key);
    if (cached != null) {
      return cached;
    }
    Map<String, String> snapshot = loadSnapshot(userId, roleId, clientId, orgId,
        warehouseId, lang);
    CACHE.put(key, snapshot);
    return snapshot;
  }

  /**
   * Replay every entry of {@code snapshot} into {@code vars} via
   * {@link VariablesSecureApp#setSessionValue}. The VSA is mutated in-place.
   */
  public static void replayInto(VariablesSecureApp vars, Map<String, String> snapshot) {
    if (vars == null || snapshot == null) {
      return;
    }
    for (Map.Entry<String, String> e : snapshot.entrySet()) {
      vars.setSessionValue(e.getKey(), e.getValue());
    }
  }

  /**
   * Invalidate the cache. Called by
   * {@code NeoCalloutService.clearMetadataCache()} so all related caches share
   * a single invalidation hook.
   */
  public static void clear() {
    CACHE.invalidateAll();
  }

  /** Visible for testing. */
  public static long size() {
    return CACHE.size();
  }

  private static String buildKey(String userId, String roleId, String clientId,
      String orgId, String warehouseId, String lang) {
    return nullSafe(userId) + "|" + nullSafe(roleId) + "|" + nullSafe(clientId)
        + "|" + nullSafe(orgId) + "|" + nullSafe(warehouseId) + "|" + nullSafe(lang);
  }

  private static String nullSafe(String s) {
    return s == null ? "" : s;
  }

  /**
   * Build the snapshot once: run {@link LoginUtils#fillSessionArguments} and
   * {@link LoginUtils#readNumberFormat} into a fresh VSA, extract the known
   * keys, return an unmodifiable map.
   */
  private static Map<String, String> loadSnapshot(String userId, String roleId,
      String clientId, String orgId, String warehouseId, String lang) {
    VariablesSecureApp vars = new VariablesSecureApp(userId, clientId, orgId, roleId, lang);
    ConnectionProvider conn = new DalConnectionProvider(false);
    try {
      LoginUtils.fillSessionArguments(conn, vars, userId, lang, "N",
          roleId, clientId, orgId, warehouseId);
    } catch (Exception e) {
      log.debug("[NEO-VARS-CACHE] fillSessionArguments failed: {}", e.getMessage());
      vars.setSessionValue("#AD_User_ID", userId);
      vars.setSessionValue("#AD_Client_ID", clientId);
      vars.setSessionValue("#AD_Org_ID", orgId);
      vars.setSessionValue("#AD_Role_ID", roleId);
      vars.setSessionValue("#AD_Language", lang);
      vars.setSessionValue("#M_Warehouse_ID", warehouseId);
      vars.setSessionValue("#User_Client", "'" + clientId + "','0'");
    }
    try {
      ConfigParameters config = ConfigParameters.retrieveFrom(RequestContext.getServletContext());
      LoginUtils.readNumberFormat(vars, config.getFormatPath());
    } catch (Exception e) {
      log.debug("[NEO-VARS-CACHE] readNumberFormat failed: {}", e.getMessage());
    }

    Map<String, String> snapshot = new LinkedHashMap<>();
    for (String key : IDENTITY_KEYS) {
      String val = vars.getSessionValue(key);
      if (val != null && !val.isEmpty()) {
        snapshot.put(key, val);
      }
    }
    for (String fmt : FORMAT_NAMES) {
      putIfNonEmpty(snapshot, "#GroupSeparator|" + fmt, vars.getSessionValue("#GroupSeparator|" + fmt));
      putIfNonEmpty(snapshot, "#DecimalSeparator|" + fmt, vars.getSessionValue("#DecimalSeparator|" + fmt));
      putIfNonEmpty(snapshot, "#FormatOutput|" + fmt, vars.getSessionValue("#FormatOutput|" + fmt));
    }
    return Collections.unmodifiableMap(new HashMap<>(snapshot));
  }

  private static void putIfNonEmpty(Map<String, String> map, String key, String val) {
    if (val != null && !val.isEmpty()) {
      map.put(key, val);
    }
  }
}
