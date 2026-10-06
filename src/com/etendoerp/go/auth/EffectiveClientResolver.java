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

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

/**
 * The tenant a credential acts on (ETP-5047): the client it carries, except the wildcard System
 * client {@code "0"}, which is resolved to the client of the credential's role.
 *
 * <p>An OAuth2 token on org {@code 0} carries client {@code "0"} ({@code OAuth2Filter} derives it
 * as {@code COALESCE(org.ad_client_id, oauth2_client.ad_client_id)}); it still acts on its role's
 * tenant. Every entry point that judges and then runs a request — the NEO bind step
 * ({@link EnvironmentRequestAuthenticator}) and MCP ({@code McpServlet}, {@code McpSessionManager})
 * — resolves it here, ONCE, and uses the one value for both the commercial access guard and the
 * {@code OBContext}: asked about {@code "0"} the guard finds no lifecycle and allows, so judging
 * the raw client let a blocked tenant through, and judging one client while running as another
 * splits the request in two.
 *
 * <p><b>Fails closed.</b> A System role legitimately has no tenant: its lookup answers nothing and
 * {@code "0"} is kept. A lookup that FAILS is different — falling back to {@code "0"} would be
 * allowed by the guard while a later lookup ran the request under the tenant, unjudged — so it
 * throws {@link ResolutionException} and the caller refuses the request.
 */
public final class EffectiveClientResolver {

  /** The System pseudo-client, the wildcard a token may carry. */
  public static final String SYSTEM_CLIENT_ID = "0";

  private static final Logger log = LogManager.getLogger(EffectiveClientResolver.class);

  private static final String ROLE_CLIENT_SQL =
      "SELECT ad_client_id FROM ad_role WHERE ad_role_id = ?";

  private EffectiveClientResolver() {
  }

  /**
   * Resolves the tenant a credential acts on.
   *
   * @param clientId the client the credential carries; may be the wildcard {@code "0"}
   * @param roleId   the credential's role
   * @return the role's client for a wildcard credential whose role belongs to a tenant; otherwise
   *         {@code clientId} unchanged ({@code "0"} for a System role or an unknown role)
   * @throws ResolutionException when the role lookup itself failed
   */
  public static String effectiveClientId(String clientId, String roleId) {
    if (!SYSTEM_CLIENT_ID.equals(clientId)) {
      return clientId;
    }
    String resolved = clientOfRole(roleId);
    if (resolved == null) {
      return clientId;
    }
    log.debug("Resolved client from role {}: {}", roleId, resolved);
    return resolved;
  }

  /**
   * The tenant client of a role, read through the Hibernate session's JDBC connection.
   *
   * @param roleId the role
   * @return the role's client, or null when the role belongs to System or does not exist
   * @throws ResolutionException when the lookup failed
   */
  static String clientOfRole(String roleId) {
    try {
      return OBDal.getInstance().getSession().doReturningWork(connection -> {
        try (PreparedStatement ps = connection.prepareStatement(ROLE_CLIENT_SQL)) {
          ps.setString(1, roleId);
          try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
              String clientId = rs.getString(1);
              return SYSTEM_CLIENT_ID.equals(clientId) ? null : clientId;
            }
          }
        }
        return null;
      });
    } catch (RuntimeException e) {
      throw new ResolutionException(roleId, e);
    }
  }

  /** The role lookup failed, so the credential's tenant is unknown: refuse, never guess. */
  public static final class ResolutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ResolutionException(String roleId, Throwable cause) {
      super("Could not resolve the client of role " + roleId, cause);
    }
  }
}
