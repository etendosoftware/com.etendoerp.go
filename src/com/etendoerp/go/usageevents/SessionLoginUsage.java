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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usageevents;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Records {@link UsageEventTypes#SESSION_LOGIN}: one successful entry into an environment.
 *
 * <p>The who-columns are passed explicitly and never read from {@code OBContext}: both login paths
 * run under the system context ({@code 0/0/0/0}, admin mode) while they mint the environment's
 * credential, so {@link UsageEvent.Builder#fromContext()} would file every login under client 0.
 * The ids are the environment being entered — the ones the issued token or session carries.</p>
 *
 * <p>Call it as the last statement of the success path, after the response is written. It never
 * throws and returns immediately; the INSERT happens on the recorder's writer thread.</p>
 */
public final class SessionLoginUsage {

  /** {@code GET /sws/go/login?userId=} — the legacy bearer path. */
  public static final String ACTION_LOGIN = "login";

  /** {@code POST /sws/go/session/environment} — the cookie-session path the SPA uses. */
  public static final String ACTION_COOKIE_LOGIN = "cookie-login";

  static final String TARGET = "environment";
  static final String PROPERTY_AUTH_METHOD = "authMethod";

  private static final Logger log = LogManager.getLogger(SessionLoginUsage.class);

  private SessionLoginUsage() {
  }

  /**
   * Record one successful environment entry. Never throws.
   *
   * @param action      {@link #ACTION_LOGIN} or {@link #ACTION_COOKIE_LOGIN}
   * @param clientId    {@code AD_Client_ID} of the environment entered
   * @param orgId       {@code AD_Org_ID} entered, or null (the writer defaults it)
   * @param userId      {@code AD_User_ID} the caller entered as
   * @param roleId      {@code AD_Role_ID} in use, or null
   * @param authMethod  how the platform session was opened ({@code password}/{@code sso}), or null;
   *                    any other value is left out
   * @param startNanos  {@link System#nanoTime()} at the start of the login handling
   */
  public static void record(String action, String clientId, String orgId, String userId,
      String roleId, String authMethod, long startNanos) {
    try {
      long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
      UsageEventRecorder.record(
          event(action, clientId, orgId, userId, roleId, authMethod, durationMs));
    } catch (Throwable t) { // NOSONAR — recording a login must never affect the login.
      log.debug("Could not record a session.login usage event.", t);
    }
  }

  static UsageEvent event(String action, String clientId, String orgId, String userId,
      String roleId, String authMethod, long durationMs) {
    UsageEvent.Builder builder = UsageEvent.builder()
        .clientId(clientId)
        .orgId(orgId)
        .userId(userId)
        .roleId(roleId)
        .eventType(UsageEventTypes.SESSION_LOGIN)
        .source(UsageEvent.SOURCE_BACKEND)
        .target(TARGET)
        .action(action)
        .outcome(UsageEvent.OUTCOME_OK)
        .durationMs(durationMs);
    if ("password".equals(authMethod) || "sso".equals(authMethod)) {
      builder.property(PROPERTY_AUTH_METHOD, authMethod);
    }
    return builder.build();
  }
}
