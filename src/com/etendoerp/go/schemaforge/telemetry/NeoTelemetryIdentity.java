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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * The common identity of a backend event (ETP-6228): the user, the environment, the account that
 * owns it and the tenant type. Every id is an internal identifier; no name or email.
 *
 * <p>An identity is resolved once per event, from one client, so its parts never mix two tenants.
 * It is captured on the thread that has the session and handed to the thread that emits
 * ({@link #runWith}); an event with no session carries {@link #NONE} and none of the properties.</p>
 */
public final class NeoTelemetryIdentity {

  /** The identity of an event with no resolvable session: adds no property. */
  public static final NeoTelemetryIdentity NONE = new NeoTelemetryIdentity(null, null, null, null);

  static final String PROP_AD_USER_ID = "ad_user_id";
  static final String PROP_AD_CLIENT_ID = "ad_client_id";
  static final String PROP_ETGO_ACCOUNT_ID = "etgo_account_id";
  static final String PROP_TENANT_TYPE = "tenant_type";
  /** Transition (ETP-6228): the former name of {@code ad_client_id}, same value. */
  static final String PROP_ACCOUNT_ID = "account_id";

  private static final ThreadLocal<NeoTelemetryIdentity> HANDED_OFF = new ThreadLocal<>();

  private final String userId;
  private final String clientId;
  private final String etgoAccountId;
  private final String tenantType;

  /**
   * Creates an identity; blank parts are absent.
   *
   * @param userId the {@code AD_User_ID}
   * @param clientId the {@code AD_Client_ID}
   * @param etgoAccountId the {@code ETGO_Account} that owns the client
   * @param tenantType {@code demo} or {@code productive}
   */
  public NeoTelemetryIdentity(String userId, String clientId, String etgoAccountId,
      String tenantType) {
    this.userId = StringUtils.trimToNull(userId);
    this.clientId = StringUtils.trimToNull(clientId);
    this.etgoAccountId = StringUtils.trimToNull(etgoAccountId);
    this.tenantType = StringUtils.trimToNull(tenantType);
  }

  /**
   * The {@code AD_Client_ID} of the identity, or {@code null}.
   *
   * @return the client id
   */
  public String getClientId() {
    return clientId;
  }

  /**
   * The properties this identity adds to an event; only the known parts, and {@code account_id}
   * (same value as {@code ad_client_id}) during the transition.
   *
   * @return an immutable, ordered map
   */
  public Map<String, Object> toProperties() {
    Map<String, Object> properties = new LinkedHashMap<>();
    put(properties, PROP_AD_USER_ID, userId);
    put(properties, PROP_AD_CLIENT_ID, clientId);
    put(properties, PROP_ACCOUNT_ID, clientId);
    put(properties, PROP_ETGO_ACCOUNT_ID, etgoAccountId);
    put(properties, PROP_TENANT_TYPE, tenantType);
    return Collections.unmodifiableMap(properties);
  }

  private static void put(Map<String, Object> properties, String key, String value) {
    if (value != null) {
      properties.put(key, value);
    }
  }

  /**
   * Runs {@code action} on the current thread with {@code identity} as the one events emitted
   * meanwhile carry, for emitters that send from a background thread that has no session.
   *
   * @param identity the identity captured on the thread that had the session
   * @param action the emission
   */
  public static void runWith(NeoTelemetryIdentity identity, Runnable action) {
    NeoTelemetryIdentity previous = HANDED_OFF.get();
    HANDED_OFF.set(identity);
    try {
      action.run();
    } finally {
      if (previous == null) {
        HANDED_OFF.remove();
      } else {
        HANDED_OFF.set(previous);
      }
    }
  }

  /**
   * The identity handed to this thread by {@link #runWith}, or {@code null}.
   *
   * @return the handed-off identity
   */
  static NeoTelemetryIdentity handedOff() {
    return HANDED_OFF.get();
  }

  /**
   * Captures the identity of the current session, on the calling thread.
   *
   * @param clientId the client the event happened under, or {@code null} for the session's
   * @param userId the user, or {@code null} for the session's
   * @return the identity, {@link #NONE} when nothing resolves
   */
  public static NeoTelemetryIdentity capture(String clientId, String userId) {
    return RuntimeNeoTelemetryIdentityResolver.INSTANCE.resolve(clientId, userId);
  }
}
