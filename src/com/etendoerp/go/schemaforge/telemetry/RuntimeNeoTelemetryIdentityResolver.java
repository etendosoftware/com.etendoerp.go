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
package com.etendoerp.go.schemaforge.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.util.OwnerAccountResolver;
import com.etendoerp.go.usage.IsolatedDalRead;

/**
 * Resolves the identity from the session ({@code OBContext}) and the client's facts.
 *
 * <p>The user comes from the explicit argument or from the session, but the session's user is used
 * only for the session's own client, so an event that names another client never carries a user of
 * a different tenant. The owner account and tenant type of the client are looked up together, in
 * one pass, and cached with a short TTL and a size bound: an ownership transfer shows up within
 * {@link #TTL_MILLIS}, and nothing is kept indefinitely.</p>
 */
final class RuntimeNeoTelemetryIdentityResolver implements NeoTelemetryIdentityResolver {

  static final RuntimeNeoTelemetryIdentityResolver INSTANCE =
      new RuntimeNeoTelemetryIdentityResolver(
          RuntimeNeoTelemetryIdentityResolver::lookupClientFacts, System::currentTimeMillis);

  static final long TTL_MILLIS = 60_000L;
  static final int MAX_ENTRIES = 256;
  /** After a failed or timed-out lookup, how long a client is not looked up again. */
  static final long NEGATIVE_TTL_MILLIS = 30_000L;
  /** How long an event waits for the isolated lookup before it goes out without the facts. */
  static final long LOOKUP_TIMEOUT_MILLIS = 5_000L;
  private static final String SYSTEM_ID = "0";
  private static final Logger log = LogManager.getLogger(RuntimeNeoTelemetryIdentityResolver.class);

  /** The owner account and tenant type of one client, resolved together. */
  static final class ClientFacts {
    private final String etgoAccountId;
    private final String tenantType;

    ClientFacts(String etgoAccountId, String tenantType) {
      this.etgoAccountId = etgoAccountId;
      this.tenantType = tenantType;
    }
  }

  private static final class CachedFacts {
    private final ClientFacts facts;
    private final long until;

    CachedFacts(ClientFacts facts, long until) {
      this.facts = facts;
      this.until = until;
    }
  }

  private static final ClientFacts NO_FACTS = new ClientFacts(null, null);

  private final Function<String, ClientFacts> lookup;
  private final LongSupplier millis;
  private final Map<String, CachedFacts> cache = new LinkedHashMap<>(16, 0.75f, true);
  /** Clients whose lookup failed, with the instant their window ends (bounded like the cache). */
  private final Map<String, Long> unavailable = new LinkedHashMap<>(16, 0.75f, true);

  RuntimeNeoTelemetryIdentityResolver(Function<String, ClientFacts> lookup, LongSupplier millis) {
    this.lookup = lookup;
    this.millis = millis;
  }

  @Override
  public NeoTelemetryIdentity resolve(String clientId, String userId) {
    try {
      return doResolve(StringUtils.trimToNull(clientId), StringUtils.trimToNull(userId));
    } catch (Exception e) {
      log.debug("Backend telemetry identity not resolved: {}", e.getMessage());
      return NeoTelemetryIdentity.NONE;
    }
  }

  private NeoTelemetryIdentity doResolve(String explicitClient, String explicitUser) {
    OBContext context = OBContext.getOBContext();
    String sessionClient = context == null || context.getCurrentClient() == null ? null
        : context.getCurrentClient().getId();
    String sessionUser = context == null || context.getUser() == null ? null
        : context.getUser().getId();
    String client = explicitClient != null ? explicitClient : sessionClient;
    String user = explicitUser;
    if (user == null && (explicitClient == null || explicitClient.equals(sessionClient))) {
      user = sessionUser;
    }
    if (isSystem(client)) {
      client = null;
    }
    if (isSystem(user)) {
      user = null;
    }
    if (client == null) {
      return user == null ? NeoTelemetryIdentity.NONE
          : new NeoTelemetryIdentity(user, null, null, null);
    }
    ClientFacts facts = factsOf(client);
    return new NeoTelemetryIdentity(user, client, facts.etgoAccountId, facts.tenantType);
  }

  private static boolean isSystem(String id) {
    return id == null || SYSTEM_ID.equals(id);
  }

  private ClientFacts factsOf(String clientId) {
    long now = millis.getAsLong();
    synchronized (this) {
      CachedFacts cached = cache.get(clientId);
      if (cached != null && cached.until > now) {
        return cached.facts;
      }
      Long retryAt = unavailable.get(clientId);
      if (retryAt != null && retryAt > now) {
        // A slow database must not make every event wait for the isolated read again.
        return NO_FACTS;
      }
    }
    // The lookup waits up to LOOKUP_TIMEOUT_MILLIS for an isolated worker: never hold the lock
    // across it, or one slow miss would serialize every emitter. Two concurrent misses of the same
    // client both look up; the later store wins, which is harmless.
    ClientFacts facts;
    try {
      facts = lookup.apply(clientId);
    } catch (Exception e) {
      log.debug("Backend telemetry client facts not resolved: {}", e.getMessage());
      facts = null;
    }
    if (facts == null) {
      // Not cached as facts; the client is only skipped for NEGATIVE_TTL_MILLIS, then retried.
      synchronized (this) {
        unavailable.put(clientId, now + NEGATIVE_TTL_MILLIS);
        if (unavailable.size() > MAX_ENTRIES) {
          unavailable.remove(unavailable.keySet().iterator().next());
        }
      }
      return NO_FACTS;
    }
    synchronized (this) {
      unavailable.remove(clientId);
      cache.put(clientId, new CachedFacts(facts, now + TTL_MILLIS));
      if (cache.size() > MAX_ENTRIES) {
        // Access order: the eldest entry is the least recently used one.
        cache.remove(cache.keySet().iterator().next());
      }
    }
    return facts;
  }

  /**
   * The owner account and the tenant type of {@code clientId}, from the database, read in its own
   * session (ETP-6386). The emitting thread is a request thread (whose transaction a failed query
   * would poison) or a sender thread (whose session nothing would ever close), and the tenant type
   * can insert an {@code AD_Preference}: the isolated read ends with a rollback, so telemetry never
   * persists anything. After {@link #LOOKUP_TIMEOUT_MILLIS}, or on any failure, there are no facts.
   */
  static ClientFacts lookupClientFacts(String clientId) {
    try {
      return IsolatedDalRead.call(() -> readClientFacts(clientId), LOOKUP_TIMEOUT_MILLIS);
    } catch (IllegalStateException e) {
      log.debug("Backend telemetry client facts not read: {}", e.getMessage());
      return null;
    }
  }

  private static ClientFacts readClientFacts(String clientId) {
    OBContext.setAdminMode(true);
    try {
      return new ClientFacts(OwnerAccountResolver.findOwnerAccountId(clientId),
          tenantType(clientId));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String tenantType(String clientId) {
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        new TenantEnvironmentLifecycleService().resolve(clientId);
    if (snapshot == null || snapshot.getType() == null) {
      return null;
    }
    return snapshot.getType() == EnvironmentAccessPolicy.EnvironmentType.DEMO ? "demo"
        : "productive";
  }
}
