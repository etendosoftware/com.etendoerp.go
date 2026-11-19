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

  private static final ClientFacts NO_FACTS = new ClientFacts(null, null);

  private final Function<String, ClientFacts> lookup;
  private final LongSupplier millis;
  private final Map<String, Long> expiry = new LinkedHashMap<>();
  private final Map<String, ClientFacts> cache = new LinkedHashMap<>();

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

  private synchronized ClientFacts factsOf(String clientId) {
    long now = millis.getAsLong();
    Long until = expiry.get(clientId);
    if (until != null && until > now) {
      return cache.get(clientId);
    }
    ClientFacts facts;
    try {
      facts = lookup.apply(clientId);
    } catch (Exception e) {
      log.debug("Backend telemetry client facts not resolved: {}", e.getMessage());
      facts = null;
    }
    if (facts == null) {
      // Not cached: a failed lookup is retried on the next event instead of hiding the facts.
      return NO_FACTS;
    }
    if (cache.size() >= MAX_ENTRIES && !cache.containsKey(clientId)) {
      String eldest = cache.keySet().iterator().next();
      cache.remove(eldest);
      expiry.remove(eldest);
    }
    cache.put(clientId, facts);
    expiry.put(clientId, now + TTL_MILLIS);
    return facts;
  }

  /** The owner account and the tenant type of {@code clientId}, from the database. */
  private static ClientFacts lookupClientFacts(String clientId) {
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
