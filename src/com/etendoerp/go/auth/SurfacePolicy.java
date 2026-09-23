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

package com.etendoerp.go.auth;

/**
 * What an environment-scoped surface requires beyond a valid credential (ETP-5455).
 *
 * <p>Declared once per servlet instead of as an {@code if} inside each credential branch: the
 * divergences ETP-5455 removes all came from a rule added to the branch its author was looking at
 * and not to the others. A policy is applied AFTER the credential is resolved, identically for
 * every scheme, so "which surfaces stay reachable while an environment is commercially blocked"
 * is answered by reading this enum.
 */
public enum SurfacePolicy {

  /** {@code /sws/neo/*} itself: ERP data, commercially gated, and reachable by OAuth2 clients. */
  NEO_API(true, true),

  /**
   * Environment data served outside {@code NeoServlet} (favorites, fiscal test mode, report
   * selectors): commercially gated like NEO, but not an OAuth2 surface.
   */
  NEO_DATA(true, false),

  /**
   * Environment-scoped helpers that must keep working while the environment is blocked (survey
   * configuration, support conversations): a blocked customer still needs to reach support.
   */
  NEO_AUXILIARY(false, false);

  private final boolean commercialAccessRequired;
  private final boolean oauth2Allowed;

  SurfacePolicy(boolean commercialAccessRequired, boolean oauth2Allowed) {
    this.commercialAccessRequired = commercialAccessRequired;
    this.oauth2Allowed = oauth2Allowed;
  }

  /** @return whether a commercially blocked environment is refused with 402 on this surface */
  public boolean isCommercialAccessRequired() {
    return commercialAccessRequired;
  }

  /** @return whether an opaque OAuth2 client-credentials token is accepted on this surface */
  public boolean isOAuth2Allowed() {
    return oauth2Allowed;
  }
}
